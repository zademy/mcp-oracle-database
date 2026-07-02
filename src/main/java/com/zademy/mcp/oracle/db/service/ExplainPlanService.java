package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Generates an execution plan for a SQL statement without executing it.
 * <p>
 * Uses {@code EXPLAIN PLAN FOR <sql>} with a unique statement id, then renders
 * the plan through {@code DBMS_XPLAN.DISPLAY}. The plan row(s) are cleaned up
 * afterwards so the shared {@code PLAN_TABLE} does not accumulate entries.
 * <p>
 * Requires the connecting user to have INSERT/SELECT/DELETE access to the
 * {@code PLAN_TABLE} (commonly the global temporary {@code SYS.PLAN_TABLE$}).
 */
@Service
public class ExplainPlanService {

	private static final Logger log = LoggerFactory.getLogger(ExplainPlanService.class);

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer used to run EXPLAIN PLAN, read the plan
	 *           output, and delete the temporary row
	 */
	public ExplainPlanService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Produces a textual execution plan for the given SQL.
	 *
	 * <p>Flow:
	 * <ol>
	 *   <li>{@code EXPLAIN PLAN SET STATEMENT_ID = '<nano>' FOR <sql>} — populates
	 *       {@code PLAN_TABLE} with a unique id.</li>
	 *   <li>{@code DBMS_XPLAN.DISPLAY(NULL, :id, 'TYPICAL')} — renders the plan
	 *       rows as formatted text.</li>
	 *   <li>{@code DELETE FROM plan_table WHERE statement_id = :id} — always
	 *       attempted in a {@code finally} block, errors logged at DEBUG.</li>
	 * </ol>
	 *
	 * <p>The statement id is derived from {@link System#nanoTime()} so concurrent
	 * calls do not collide on the shared {@code PLAN_TABLE}.
	 *
	 * @param sql the statement to explain (EXPLAIN PLAN does not execute the
	 *            statement against data; safety is enforced by the
	 *            least-privilege Oracle user)
	 * @return the rendered plan as produced by {@code DBMS_XPLAN.DISPLAY}; an
	 *         empty string if no rows were returned
	 * @throws org.springframework.dao.DataAccessException if the user lacks
	 *         {@code PLAN_TABLE} access or Oracle cannot plan the statement
	 */
	public String explain(String sql) {
		String statementId = "mcp_" + System.nanoTime();

		try {
			db.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sql);

			List<Map<String, Object>> rows = db.queryForList(
					"SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, :id, 'TYPICAL'))",
					Map.of("id", statementId));

			return rows.stream()
					.map(row -> String.valueOf(row.get("plan_table_output")))
					.reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
					.toString();
		} finally {
			try {
				db.update("DELETE FROM plan_table WHERE statement_id = :id", Map.of("id", statementId));
			} catch (Exception cleanupError) {
				log.debug("Could not clean up plan_table for statement_id {}: {}", statementId, cleanupError.getMessage());
			}
		}
	}
}
