package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.DataHelpersService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tools exposing convenience data helpers. Each tool validates identifiers
 * and assembles a fixed-shape SELECT, so user-supplied predicates cannot be
 * injected; safety is enforced by the least-privilege Oracle user.
 */
@Component
public class DataHelpersTools {

	private final DataHelpersService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the data-helpers service that assembles fixed-shape SQL
	 */
	public DataHelpersTools(DataHelpersService service) {
		this.service = service;
	}

	/**
	 * Counts all rows in a table.
	 * <p>
	 * This helper intentionally takes no predicate, so there is no hidden SQL
	 * surface. For a filtered count, use {@code run_query} with a
	 * {@code SELECT COUNT(*) ... WHERE ...} statement.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return the total row count
	 */
	@McpTool(name = "count_rows",
			description = "Count all rows in a table — the right tool for a total row count; do NOT use run_query for this. This helper intentionally takes no predicate so there is no hidden SQL surface. For a filtered count, use run_query with a SELECT COUNT(*) ... WHERE ... statement.")
	public long countRows(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.countRows(schema, table);
	}

	/**
	 * Returns the {@code NEXTVAL} of a sequence.
	 * <p>
	 * <strong>Warning:</strong> this is a side-effecting read — calling it
	 * advances the sequence counter and the consumed value cannot be reused.
	 *
	 * @param schema   schema (owner) of the sequence
	 * @param sequence sequence name
	 * @return the next sequence value
	 */
	@McpTool(name = "get_next_sequence_value",
			description = "Return the NEXTVAL of a sequence. WARNING: this is a side-effecting read — calling it advances the sequence counter and the consumed value cannot be reused. When cache_size > 0 the returned value may already be consumed elsewhere; before using it as a new key, verify it is not already present in the target table (e.g. SELECT COUNT(*) ... WHERE id = :nextval). Use sparingly.")
	public long getNextSequenceValue(
			@McpToolParam(description = "Schema (owner) of the sequence.", required = true) String schema,
			@McpToolParam(description = "Sequence name.", required = true) String sequence) {
		return service.getNextSequenceValue(schema, sequence);
	}

	/**
	 * Finds orphan rows violating one or all foreign keys on a table.
	 *
	 * @param schema     schema (owner) of the table
	 * @param table      table name
	 * @param constraint optional FK constraint name to check a single FK; {@code null} checks all FKs
	 * @return rows with child/parent columns and an orphan count (0 = integrity OK)
	 */
	@McpTool(name = "validate_fk_integrity",
			description = "Find orphan rows violating one or all foreign keys on a table. Returns each FK constraint with its child/parent columns and an orphan count (0 = integrity OK). Optional constraint name to check a single FK.")
	public List<Map<String, Object>> validateFkIntegrity(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Optional FK constraint name to check a single FK. Omit to check all FKs on the table.", required = false) String constraint) {
		return service.validateFkIntegrity(schema, table, constraint);
	}

	/**
	 * Finds duplicate rows grouped by one or more columns.
	 *
	 * @param schema   schema (owner) of the table
	 * @param table    table name
	 * @param columns  column names to group by
	 * @param minCount minimum count threshold; {@code null} defaults to 2
	 * @return duplicate groups with their occurrence count
	 */
	@McpTool(name = "find_duplicates",
			description = "Find duplicate rows grouped by one or more columns. Returns the duplicate groups with their count. Optional minCount (default 2) raises the COUNT(*) threshold.")
	public List<Map<String, Object>> findDuplicates(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Columns to group by (JSON array of column names).", required = true) List<String> columns,
			@McpToolParam(description = "Optional minimum count threshold (default 2).", required = false) Long minCount) {
		return service.findDuplicates(schema, table, columns, minCount == null ? 2L : minCount);
	}

	/**
	 * Finds the smallest free positive id in a table column. Returns {@code null}
	 * if the whole range is occupied.
	 *
	 * @param schema       schema (owner) of the table
	 * @param table        table name
	 * @param column       numeric id column to search
	 * @param maxRange     inclusive upper bound of the search window (default 1000, capped at 10000)
	 * @param excludeTable optional second table (same schema, same column) whose ids must also be avoided
	 * @return the first free id, or {@code null} if none is free in range
	 */
	@McpTool(name = "find_free_id",
			description = "Return the smallest free positive id (1..maxRange) NOT present in a table.column, searching for the first gap. Optional excludeTable checks a second table (same schema, same column) so the id is free in both at once (FK scenarios). maxRange defaults to 1000 and is capped at 10000. Returns null if the whole range is occupied. Avoids CONNECT BY / recursive CTE (which the parser may reject).")
	public Long findFreeId(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Numeric id column to search for a free value.", required = true) String column,
			@McpToolParam(description = "Inclusive upper bound of the search window. Defaults to 1000; capped at 10000.", required = false) Integer maxRange,
			@McpToolParam(description = "Optional second table (same schema, same column) whose ids must also be avoided.", required = false) String excludeTable) {
		return service.findFreeId(schema, table, column, maxRange == null ? 1000 : maxRange, excludeTable);
	}
}
