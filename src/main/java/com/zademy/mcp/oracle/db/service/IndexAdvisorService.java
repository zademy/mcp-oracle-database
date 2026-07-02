package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.model.IndexSuggestion;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only index and query-performance advisory. Three capabilities:
 * <ul>
 *   <li>{@link #listUnusedIndexes} &mdash; inventory indexes + best-effort usage
 *       info from {@code V$OBJECT_USAGE}.</li>
 *   <li>{@link #suggestIndex} &mdash; run {@code EXPLAIN PLAN}, find full scans,
 *       and propose a {@code CREATE INDEX} DDL (returned as text, never run).</li>
 *   <li>{@link #runSqlTuningAdvisor} &mdash; drive Oracle's {@code DBMS_SQLTUNE}
 *       and return the tuning report. Requires the {@code ADVISOR} privilege;
 *       fails with a clear message if it is missing.</li>
 * </ul>
 * None of these retrieves rows for the analysed SQL. Note that {@link #suggestIndex}
 * runs {@code EXPLAIN PLAN}, which parses and optimises the statement on the server
 * (optimizer-only &mdash; it does not execute the statement against data).
 */
@Service
public class IndexAdvisorService {

	private static final Logger log = LoggerFactory.getLogger(IndexAdvisorService.class);

	/** Matches Oracle quoted identifiers in predicate text (e.g. {@code "DEPTNO"} or mixed-case {@code "deptNo"}). */
	private static final Pattern PREDICATE_COLUMN =
			Pattern.compile("\"([A-Za-z_][A-Za-z0-9_$#]{0,127})\"", Pattern.CASE_INSENSITIVE);

	/** Privilege/feature errors that mean "ADVISOR role missing or unavailable". Word-boundary so ORA-01031 != ORA-010310. */
	private static final Pattern ADVISOR_MISSING =
			Pattern.compile("ORA-01031\\b|ORA-13616\\b|ORA-24470\\b");

	private static final int MAX_INDEX_NAME = 128;

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer (used for EXPLAIN PLAN, dictionary queries
	 *           and {@code DBMS_SQLTUNE})
	 */
	public IndexAdvisorService(OracleDataAccess db) {
		this.db = db;
	}

	// ---- list_unused_indexes --------------------------------------------------

	/**
	 * List indexes with their columns and best-effort usage information.
	 * <p>
	 * Oracle records index usage in {@code V$OBJECT_USAGE} only for indexes on
	 * which a DBA has run {@code ALTER INDEX ... MONITORING USAGE}, and visibility
	 * is limited to indexes the connecting user can see. The {@code likely_unused}
	 * column is {@code YES}/{@code NO} when monitored, or {@code UNKNOWN}
	 * otherwise &mdash; enable monitoring (a DBA action) for definitive answers.
	 */
	public List<Map<String, Object>> listUnusedIndexes(String schema) {
		return db.queryForList("""
				SELECT i.owner AS index_owner, i.index_name,
				       i.table_owner, i.table_name,
				       (SELECT LISTAGG(ic.column_name, ', ') WITHIN GROUP (ORDER BY ic.column_position)
				        FROM all_ind_columns ic
				        WHERE ic.index_owner = i.owner AND ic.index_name = i.index_name) AS columns,
				       i.uniqueness, i.status, i.last_analyzed,
				       COALESCE(u.monitoring, 'NO') AS monitoring,
				       CASE WHEN u.used = 'NO' AND u.monitoring = 'YES' THEN 'YES'
				            WHEN u.used = 'YES' THEN 'NO'
				            ELSE 'UNKNOWN' END AS likely_unused,
				       u.start_monitoring, u.end_monitoring
				FROM all_indexes i
				LEFT JOIN v$object_usage u
				  ON u.index_name = i.index_name AND u.table_name = i.table_name
				WHERE (:schema IS NULL OR i.table_owner = :schema)
				ORDER BY i.table_owner, i.table_name, i.index_name
				""", BindParams.of("schema", schema));
	}

	// ---- suggest_index --------------------------------------------------------

	/**
	 * Analyse a SQL statement and, if a table is accessed by full scan, propose a
	 * {@code CREATE INDEX} statement. The DDL is returned as text and never
	 * executed; apply it manually only after DBA review.
	 *
	 * @param sql         the statement to analyse (typically SELECT; also works for DML)
	 * @param targetTable optional table name to focus on when several are scanned
	 */
	public IndexSuggestion suggestIndex(String sql, String targetTable) {
		String statementId = "mcp_sidx_" + System.nanoTime();
		try {
			db.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sql);

			List<Map<String, Object>> planRows = db.queryForList("""
					SELECT object_owner, object_name, operation, options, cost,
					       access_predicates, filter_predicates
					FROM plan_table
					WHERE statement_id = :id AND object_name IS NOT NULL
					ORDER BY id
					""", Map.of("id", statementId));

			Map<String, Object> candidate = findFullScanCandidate(planRows, targetTable);
			if (candidate == null) {
				return IndexSuggestion.empty(targetTable == null
						? "No TABLE ACCESS FULL operations found; the query already uses indexes."
						: "No TABLE ACCESS FULL found for " + targetTable + ".");
			}

			String schema = str(candidate.get("object_owner"));
			String table = str(candidate.get("object_name"));
			List<Map<String, Object>> colStats = db.queryForList("""
					SELECT column_name, num_distinct
					FROM all_col_statistics
					WHERE owner = :owner AND table_name = :table
					""", Map.of("owner", schema, "table", table));

			return buildSuggestion(candidate, colStats);
		} finally {
			cleanupPlan(statementId);
		}
	}

	// ---- run_sql_tuning_advisor ----------------------------------------------

	/**
	 * Run Oracle's SQL Tuning Advisor on a statement and return its TEXT report.
	 * Requires the {@code ADVISOR} privilege; if missing, returns a clear guidance
	 * message instead of throwing.
	 */
	public String runSqlTuningAdvisor(String sql) {
		String taskName = "mcp_sta_" + System.nanoTime();
		try {
			db.queryForObject("""
					SELECT DBMS_SQLTUNE.CREATE_SQL_TUNING_TASK(
					       sql_text => :sql, scope => 'COMPREHENSIVE',
					       time_limit => 30, task_name => :task,
					       description => 'MCP SQL Tuning Advisor') FROM dual
					""", Map.of("sql", sql, "task", taskName), String.class);

			db.update("BEGIN DBMS_SQLTUNE.EXECUTE_TUNING_TASK(task_name => :task); END;",
					Map.of("task", taskName));

			return db.queryForObject("""
					SELECT DBMS_SQLTUNE.REPORT_TUNING_TASK(task_name => :task,
					       type => 'TEXT', level => 'TYPICAL', section => 'ALL') FROM dual
					""", Map.of("task", taskName), String.class);
		} catch (Exception e) {
			String msg = String.valueOf(e.getMessage());
			if (isMissingAdvisor(msg)) {
				return "SQL Tuning Advisor requires the ADVISOR privilege, which the current "
						+ "Oracle user does not have. Have a DBA run: GRANT ADVISOR TO <mcp_user>; "
						+ "(see db/setup_least_privilege_user.sql, section 8).";
			}
			log.warn("SQL Tuning Advisor failed for task {}: {}", taskName, msg, e);
			return "SQL Tuning Advisor failed; the detail has been logged server-side. "
					+ "If the ADVISOR privilege is missing, grant it (see db/setup_least_privilege_user.sql, "
					+ "section 8); otherwise inspect the server logs.";
		} finally {
			try {
				db.update("BEGIN DBMS_SQLTUNE.DROP_TUNING_TASK(task_name => :task); END;",
						Map.of("task", taskName));
			} catch (Exception cleanupError) {
				log.debug("Could not drop tuning task {}: {}", taskName, cleanupError.getMessage());
			}
		}
	}

	// ---- pure analysis helpers (unit-testable, no DB) -------------------------

	/**
	 * Pick the worst-cost {@code TABLE ACCESS FULL} from plan rows. Returns the
	 * chosen row, or {@code null} when none match.
	 *
	 * @param planRows    rows shaped like {@code plan_table} (keys:
	 *                    {@code object_owner, object_name, operation, options, cost})
	 * @param targetTable optional table-name filter (case-insensitive)
	 */
	static Map<String, Object> findFullScanCandidate(List<Map<String, Object>> planRows, String targetTable) {
		if (planRows == null || planRows.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> fullScans = new ArrayList<>();
		for (Map<String, Object> row : planRows) {
			if (!"TABLE ACCESS".equals(str(row.get("operation"))) || !"FULL".equals(str(row.get("options")))) {
				continue;
			}
			String table = str(row.get("object_name"));
			if (table == null) {
				continue;
			}
			if (targetTable != null && !targetTable.equalsIgnoreCase(table)) {
				continue;
			}
			fullScans.add(row);
		}
		if (fullScans.isEmpty()) {
			return null;
		}
		// Worst cost wins; break ties deterministically by object_name so a NULL/tied
		// cost never selects an arbitrary candidate across runs.
		return fullScans.stream().max(
				Comparator.comparingLong(IndexAdvisorService::toLongCost)
						.thenComparing(r -> str(r.get("object_name")), Comparator.nullsLast(Comparator.naturalOrder()))
		).orElse(null);
	}

	/**
	 * Build a recommendation from a full-scan row and that table's column stats.
	 * Pure: deterministic, no I/O.
	 *
	 * @param candidate a plan row produced by {@link #findFullScanCandidate}
	 * @param colStats  rows shaped like {@code all_col_statistics}
	 *                  (keys {@code column_name, num_distinct})
	 */
	static IndexSuggestion buildSuggestion(Map<String, Object> candidate, List<Map<String, Object>> colStats) {
		String schema = str(candidate.get("object_owner"));
		String table = str(candidate.get("object_name"));
		List<String> predicateCols = extractColumns(candidate.get("access_predicates"), candidate.get("filter_predicates"));

		if (predicateCols.isEmpty()) {
			return new IndexSuggestion(true, schema, table, "TABLE ACCESS FULL",
					List.of(), List.of(), null,
					"Full scan detected on " + table + " but no filter/join predicate columns were "
							+ "exposed in the plan; an index may not help unless the query has selective predicates.");
		}

		Map<String, Long> distinctByCol = loadDistinctCounts(colStats);
		List<String> ranked = new ArrayList<>(predicateCols);
		ranked.sort(Comparator.comparingLong((String c) -> distinctByCol.getOrDefault(c.toUpperCase(), 0L)).reversed());

		List<String> chosen = new ArrayList<>(ranked.subList(0, Math.min(ranked.size(), 2)));
		String ddl = buildCreateIndexDdl(schema, table, chosen);

		long best = distinctByCol.getOrDefault(chosen.get(0).toUpperCase(), 0L);
		String reasoning = "Full scan on " + schema + "." + table + " with predicate column(s) "
				+ predicateCols + ". Indexing " + chosen + " (num_distinct=" + best
				+ " for the leading column) should let Oracle use an index range scan.";

		return new IndexSuggestion(true, schema, table, "TABLE ACCESS FULL",
				predicateCols, chosen, ddl, reasoning);
	}

	/**
	 * Builds a {@code CREATE INDEX} DDL string from DB-sourced identifiers
	 * (no validation needed). The index name is truncated if it exceeds the
	 * Oracle 128-byte limit.
	 *
	 * @param schema  owner of the index and table
	 * @param table   target table name
	 * @param columns ordered list of columns to index
	 * @return a ready-to-run {@code CREATE INDEX} statement
	 */
	private static String buildCreateIndexDdl(String schema, String table, List<String> columns) {
		String colsCsv = columns.stream()
				.map(SqlIdentifiers::quoteTrusted)
				.reduce((a, b) -> a + ", " + b)
				.orElse("");
		String indexName = truncateIndexName("IX_" + table + "_" + String.join("_", columns));
		return "CREATE INDEX " + SqlIdentifiers.qualifiedTrusted(schema, indexName)
				+ " ON " + SqlIdentifiers.qualifiedTrusted(schema, table) + " (" + colsCsv + ")";
	}

	/**
	 * Best-effort truncation so the generated index name fits the Oracle 12c+
	 * 128-byte identifier limit. Truncation is character-based (Oracle's limit is
	 * bytes, so multibyte names could still overflow when a DBA applies the DDL). A
	 * 32-bit hash suffix greatly reduces &mdash; but does not eliminate &mdash; the
	 * chance of two different long names colliding after truncation.
	 */
	private static String truncateIndexName(String name) {
		if (name.length() <= MAX_INDEX_NAME) {
			return name;
		}
		String hash = Integer.toHexString(name.hashCode());
		int keep = Math.max(MAX_INDEX_NAME - 1 - hash.length(), 1);
		return name.substring(0, keep) + "_" + hash;
	}

	/**
	 * Extracts the ordered, de-duplicated list of column names referenced in
	 * the access and filter predicates of an EXPLAIN PLAN row.
	 *
	 * @param accessPredicates raw value of the {@code access_predicates} column (may be null)
	 * @param filterPredicates  raw value of the {@code filter_predicates} column (may be null)
	 * @return ordered list of column names, case-insensitively deduplicated
	 */
	private static List<String> extractColumns(Object accessPredicates, Object filterPredicates) {
		Set<String> seenUpper = new LinkedHashSet<>();
		List<String> ordered = new ArrayList<>();
		collectPredicateColumns(accessPredicates, seenUpper, ordered);
		collectPredicateColumns(filterPredicates, seenUpper, ordered);
		return ordered;
	}

	/** Deduplicate case-insensitively (keys upper-cased) while preserving first-seen casing. */
	private static void collectPredicateColumns(Object predicates, Set<String> seenUpper, List<String> ordered) {
		if (predicates == null) {
			return;
		}
		Matcher m = PREDICATE_COLUMN.matcher(String.valueOf(predicates));
		while (m.find()) {
			String col = m.group(1);
			if (seenUpper.add(col.toUpperCase())) {
				ordered.add(col);
			}
		}
	}

	/**
	 * Builds a {@code column_name -> num_distinct} map (upper-cased keys) from
	 * {@code ALL_COL_STATISTICS} rows.
	 */
	private static Map<String, Long> loadDistinctCounts(List<Map<String, Object>> colStats) {
		Map<String, Long> out = new LinkedHashMap<>();
		if (colStats == null) {
			return out;
		}
		for (Map<String, Object> row : colStats) {
			String col = str(row.get("column_name"));
			if (col != null) {
				out.put(col.toUpperCase(), toLong(row.get("num_distinct")));
			}
		}
		return out;
	}

	private static long toLongCost(Map<String, Object> row) {
		return toLong(row.get("cost"));
	}

	private static long toLong(Object v) {
		if (v instanceof Number n) {
			return n.longValue();
		}
		if (v != null) {
			try {
				return Long.parseLong(String.valueOf(v).trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return 0L;
	}

	private static String str(Object v) {
		return v == null ? null : String.valueOf(v);
	}

	/**
	 * Detects whether an Oracle error message indicates a missing
	 * {@code ADVISOR} privilege, by matching specific ORA codes only
	 * (word-bounded so an unrelated ORA-01031x code is not misreported).
	 *
	 * @param message the raw Oracle error message (may be null)
	 * @return {@code true} if the message indicates the ADVISOR privilege is missing
	 */
	private static boolean isMissingAdvisor(String message) {
		// Match specific Oracle error codes only (word-bounded), so an unrelated error
		// that merely contains the word "ADVISOR" or a longer ORA-01031x code is not
		// misreported as a missing privilege.
		return message != null && ADVISOR_MISSING.matcher(message.toUpperCase()).find();
	}

	/**
	 * Best-effort cleanup of the {@code PLAN_TABLE} row created by
	 * {@link #suggestIndex}. Errors are logged at DEBUG and swallowed because
	 * the caller has already received the analysis result.
	 *
	 * @param statementId the unique statement id used by the EXPLAIN PLAN
	 */
	private void cleanupPlan(String statementId) {
		try {
			db.update("DELETE FROM plan_table WHERE statement_id = :id", Map.of("id", statementId));
		} catch (Exception cleanupError) {
			log.debug("Could not clean up plan_table for {}: {}", statementId, cleanupError.getMessage());
		}
	}
}
