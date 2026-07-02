package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.model.IndexSuggestion;
import com.zademy.mcp.oracle.db.service.IndexAdvisorService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Index and query-performance advisory tools. All three are read-only analysis:
 * none of them executes the analysed SQL against data.
 */
@Component
public class IndexAdvisorTools {

	private final IndexAdvisorService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the index-advisor service that performs read-only analysis
	 */
	public IndexAdvisorTools(IndexAdvisorService service) {
		this.service = service;
	}

	/**
	 * Lists indexes with their columns and best-effort usage information.
	 * <p>
	 * The {@code likely_unused} flag is {@code YES}/{@code NO} when index
	 * monitoring is enabled, otherwise {@code UNKNOWN}. Definitive "unused"
	 * answers require a DBA to run {@code ALTER INDEX ... MONITORING USAGE}.
	 *
	 * @param schema table-owner filter; {@code null} lists across all accessible schemas
	 * @return rows with index name, columns, uniqueness, and usage flag
	 */
	@McpTool(name = "list_unused_indexes",
			description = "List indexes with their columns and best-effort usage information. "
					+ "The likely_unused flag is YES/NO when index monitoring is enabled, otherwise UNKNOWN. "
					+ "Definitive 'unused' answers require a DBA to run ALTER INDEX ... MONITORING USAGE.")
	public List<Map<String, Object>> listUnusedIndexes(
			@McpToolParam(description = "Schema (table owner). Omit to list across all accessible schemas.",
					required = false) String schema) {
		return service.listUnusedIndexes(schema);
	}

	/**
	 * Analyses a SQL statement via EXPLAIN PLAN and, when a table is read by
	 * full scan, proposes a {@code CREATE INDEX} statement.
	 * <p>
	 * The recommended DDL is returned as text and is never executed. Apply it
	 * manually only after DBA review.
	 *
	 * @param sql         the SQL statement to analyse (typically SELECT; DML is also supported)
	 * @param targetTable optional table name to focus on when several tables are scanned
	 * @return an {@link IndexSuggestion} with the proposed DDL, or an empty suggestion if no full scan was found
	 */
	@McpTool(name = "suggest_index",
			description = "Analyse a SQL statement via EXPLAIN PLAN and, when a table is read by full scan, "
					+ "propose a CREATE INDEX statement. Returns the recommended DDL as text; it is never executed. "
					+ "Apply it manually only after DBA review.")
	public IndexSuggestion suggestIndex(
			@McpToolParam(description = "The SQL statement to analyse (typically SELECT; DML is also supported).",
					required = true) String sql,
			@McpToolParam(description = "Optional table name to focus on when several tables are scanned.",
					required = false) String targetTable) {
		return service.suggestIndex(sql, targetTable);
	}

	/**
	 * Runs Oracle's SQL Tuning Advisor ({@code DBMS_SQLTUNE}) on a statement and
	 * returns its TEXT report with recommendations.
	 * <p>
	 * Requires the {@code ADVISOR} privilege; returns a clear guidance message
	 * if it is missing.
	 *
	 * @param sql the SQL statement to tune
	 * @return the tuning-advisor report text
	 */
	@McpTool(name = "run_sql_tuning_advisor",
			description = "Run Oracle's SQL Tuning Advisor (DBMS_SQLTUNE) on a statement and return its "
					+ "TEXT report with recommendations (indexes, SQL profiles, statistics, rewrites). "
					+ "Requires the ADVISOR privilege; returns a clear guidance message if it is missing.")
	public String runSqlTuningAdvisor(
			@McpToolParam(description = "The SQL statement to tune.", required = true) String sql) {
		return service.runSqlTuningAdvisor(sql);
	}
}
