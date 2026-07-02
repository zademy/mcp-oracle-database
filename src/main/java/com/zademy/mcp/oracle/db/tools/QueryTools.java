package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.QueryService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * MCP tools that run SELECT statements (read path). Oracle errors (including
 * privilege denials for disallowed statements) are returned as an explanatory
 * message string rather than crashing the tool call.
 */
@Component
public class QueryTools {

	private final QueryService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the query service that runs SELECT statements
	 */
	public QueryTools(QueryService service) {
		this.service = service;
	}

	/**
	 * Runs a single SELECT statement and returns the result set.
	 *
	 * @param sql a single SELECT statement
	 * @return a {@code QueryResult} on success, or an Oracle error string on failure
	 */
	@McpTool(name = "run_query",
			description = "Run a single SELECT statement and return the result set. Optional offset/limit enable stateless pagination (non-deterministic without an ORDER BY in the SQL). Non-SELECT statements or privilege failures return an Oracle error string. NOTE: recursive constructs like CONNECT BY and WITH (CTE) may be rejected by the SQL parser; for set differences use MINUS, and for row-number/gap problems prefer computing on the returned result set rather than relying on recursive SQL.")
	public Object runQuery(
			@McpToolParam(description = "A single SELECT statement.", required = true) String sql,
			@McpToolParam(description = "0-based row offset for pagination. Defaults to 0.", required = false) Integer offset,
			@McpToolParam(description = "Maximum rows to return for this page. Capped at the server max-rows. Defaults to the server max.", required = false) Integer limit) {
		try {
			return service.runQuery(sql, offset, limit);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (DataAccessException ex) {
			return SqlErrorHints.enrich(ex.getMessage());
		}
	}

	/**
	 * Returns the first N rows of a table as a quick data preview.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @param rows   number of rows to return; {@code null} or zero uses the server default
	 * @return a {@code QueryResult} with the sampled rows
	 */
	@McpTool(name = "get_sample_data",
			description = "Return the first N rows of a table as a quick data preview.")
	public Object getSampleData(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Number of rows to return. Defaults to the server default (10).", required = false) Integer rows) {
		return service.sampleData(schema, table, rows == null ? 0 : rows);
	}
}
