package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.DmlDryRunResult;
import com.zademy.mcp.oracle.db.model.DmlPreviewResult;
import com.zademy.mcp.oracle.db.model.DmlResult;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs user-supplied DML (INSERT/UPDATE/DELETE/MERGE). Safety is enforced by
 * the least-privilege Oracle user, which has per-object
 * SELECT/INSERT/UPDATE/DELETE grants but no DDL/DCL privileges or tablespace
 * quota; disallowed statements are rejected by Oracle (for example
 * {@code ORA-01031: insufficient privileges}).
 *
 * <p>Three execution modes:
 * <ul>
 *   <li>{@link #executeDml(String)} — commit immediately.</li>
 *   <li>{@link #previewDml(String)} — non-executing dry-run for UPDATE/DELETE
 *       that previews the rows the statement would touch. INSERT and MERGE are
 *       not previewable here.</li>
 *   <li>{@link #rollbackFirstDml(String)} — execute the statement inside a
 *       transaction that is immediately rolled back, proving validity and
 *       revealing the affected row count without persisting anything.</li>
 * </ul>
 */
@Service
public class DmlService {

	private static final Logger log = LoggerFactory.getLogger(DmlService.class);

	private final OracleDataAccess db;
	private final OracleMcpProperties props;
	private final TransactionTemplate tx;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db        the data access layer that executes the statement
	 * @param props     the {@code oracle.mcp.*} properties (sample-row default)
	 * @param txManager the Spring transaction manager used by rollback-first
	 */
	public DmlService(OracleDataAccess db, OracleMcpProperties props,
			PlatformTransactionManager txManager) {
		this.db = db;
		this.props = props;
		this.tx = new TransactionTemplate(txManager);
	}

	/**
	 * Executes a single DML statement, committing the change.
	 *
	 * @param sql the AI-provided SQL (a single INSERT/UPDATE/DELETE/MERGE)
	 * @return a {@link DmlResult} carrying the detected kind and rows affected
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 *         (including privilege denials for disallowed statements)
	 */
	public DmlResult executeDml(String sql) {
		int rowsAffected = db.update(sql);
		return new DmlResult(detectKind(sql), rowsAffected);
	}

	/**
	 * Non-executing dry-run for UPDATE and DELETE. The statement's WHERE clause
	 * is extracted and run as a SELECT so the AI client can see exactly which
	 * rows would be affected before committing. INSERT (no WHERE to extract) and
	 * MERGE (Q2 decision) are refused with an explanatory message.
	 *
	 * @param sql a single UPDATE or DELETE statement
	 * @return a {@link DmlPreviewResult} with the affected-row count and a sample
	 * @throws IllegalArgumentException if the statement is not a previewable
	 *         UPDATE/DELETE
	 */
	public DmlPreviewResult previewDml(String sql) {
		String kind = detectKind(sql);

		if ("INSERT".equalsIgnoreCase(kind)) {
			throw new IllegalArgumentException("execute_dml_preview does not support INSERT (there is no WHERE clause "
					+ "to preview affected rows). Use execute_dml_rollback_first to dry-run "
					+ "INSERT statements, or execute_dml to apply them.");
		}
		if ("MERGE".equalsIgnoreCase(kind)) {
			throw new IllegalArgumentException("execute_dml_preview does not support MERGE. Use execute_dml_rollback_first "
					+ "to dry-run MERGE statements, or execute_dml to apply them.");
		}
		if (!"UPDATE".equalsIgnoreCase(kind) && !"DELETE".equalsIgnoreCase(kind)) {
			throw new IllegalArgumentException("execute_dml_preview only supports UPDATE or DELETE statements. "
					+ "Use run_query for SELECT or execute_dml for INSERT/MERGE.");
		}

		Target target = extractTarget(sql);
		if (target == null) {
			throw new IllegalArgumentException("Unable to extract the target table and WHERE clause for preview. "
					+ "Preview only supports simple UPDATE/DELETE forms.");
		}

		int sampleSize = props.defaultSampleRows() > 0 ? props.defaultSampleRows() : 10;
		String whereClause = target.whereClause().isEmpty() ? "" : " WHERE " + target.whereClause();
		String countSql = "SELECT COUNT(*) FROM " + target.table() + whereClause;
		String sampleSql = "SELECT * FROM " + target.table() + whereClause
				+ " FETCH FIRST " + sampleSize + " ROWS ONLY";

		Integer affected = db.queryForObject(countSql, Integer.class);
		int affectedCount = affected == null ? 0 : affected;
		List<Map<String, Object>> sample = db.queryForList(sampleSql);
		List<String> columns = sample.isEmpty() ? List.of() : new ArrayList<>(sample.get(0).keySet());

		return new DmlPreviewResult(
				kind,
				target.table(),
				affectedCount,
				columns,
				sample,
				sample.size(),
				"Dry-run preview: the statement was NOT executed. "
						+ affectedCount + " row(s) would be affected. "
						+ "Call execute_dml to apply, or execute_dml_rollback_first to dry-run the write.");
	}

	/**
	 * Executes the statement inside a transaction that is always rolled back.
	 * This proves the statement is valid (it really runs against Oracle) and
	 * reveals the affected row count, but persists nothing. The caller can then
	 * invoke {@link #executeDml(String)} to commit the change.
	 *
	 * @param sql a single INSERT/UPDATE/DELETE/MERGE statement
	 * @return a {@link DmlDryRunResult} with the reported row count and {@code rolledBack=true}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public DmlDryRunResult rollbackFirstDml(String sql) {
		Integer rows = tx.execute(status -> {
			int affected = db.update(sql);
			status.setRollbackOnly();
			return affected;
		});

		int rowsAffected = rows == null ? 0 : rows;
		return new DmlDryRunResult(
				detectKind(sql),
				rowsAffected,
				true,
				"Statement executed and rolled back; nothing was persisted. "
						+ "Call execute_dml to apply the change.");
	}

	/**
	 * Best-effort detection of the top-level statement kind via JSqlParser, used
	 * only to label the result. Parsing failures yield {@code "UNKNOWN"}; the
	 * statement is still forwarded to Oracle, which is the authoritative gate.
	 *
	 * @param sql the statement to classify
	 * @return {@code "INSERT"}, {@code "UPDATE"}, {@code "DELETE"},
	 *         {@code "MERGE"} or {@code "UNKNOWN"}
	 */
	private static String detectKind(String sql) {
		try {
			Statement stmt = CCJSqlParserUtil.parse(sql);
			if (stmt instanceof Insert) {
				return "INSERT";
			}
			if (stmt instanceof Update) {
				return "UPDATE";
			}
			if (stmt instanceof Delete) {
				return "DELETE";
			}
			if (stmt instanceof Merge) {
				return "MERGE";
			}
			return "UNKNOWN";
		} catch (JSQLParserException ex) {
			return "UNKNOWN";
		}
	}

	/**
	 * Parse the statement and pull out the target table reference and the
	 * serialized WHERE expression for the preview SELECT.
	 *
	 * @return the {@link Target}, or {@code null} if the shape is not a simple
	 *         UPDATE/DELETE we can preview
	 */
	private Target extractTarget(String sql) {
		try {
			Statement stmt = CCJSqlParserUtil.parse(sql);
			if (stmt instanceof Update u) {
				Table t = u.getTable();
				if (t == null) {
					return null;
				}
				Expression where = u.getWhere();
				return new Target(t.toString(), where == null ? "" : where.toString());
			}
			if (stmt instanceof Delete d) {
				Table t = d.getTable();
				if (t == null) {
					return null;
				}
				Expression where = d.getWhere();
				return new Target(t.toString(), where == null ? "" : where.toString());
			}
			return null;
		} catch (Exception ex) {
			log.debug("preview target extraction failed: {}", ex.getMessage());
			return null;
		}
	}

	/** Internal holder for the table reference + serialized WHERE of a DML. */
	private record Target(String table, String whereClause) {
	}
}
