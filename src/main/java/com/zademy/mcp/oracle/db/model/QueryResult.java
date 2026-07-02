package com.zademy.mcp.oracle.db.model;

import java.util.List;
import java.util.Map;

/**
 * Result of a read query (SELECT) returned to the MCP client.
 *
 * @param columns   ordered column labels
 * @param rows      each row as a label-&gt;value map
 * @param rowCount  number of rows actually returned in this page
 * @param truncated true when the page is full (more rows likely exist beyond the limit)
 * @param rowLimit  the configured global maximum rows ({@code oracle.mcp.max-rows})
 * @param offset    the row offset applied to the query (0 when not paginating)
 * @param limit     the fetch size applied ({@code rowLimit} when not paginating)
 */
public record QueryResult(
		List<String> columns,
		List<Map<String, Object>> rows,
		int rowCount,
		boolean truncated,
		int rowLimit,
		int offset,
		int limit) {
}
