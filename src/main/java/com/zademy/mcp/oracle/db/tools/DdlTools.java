package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.DdlService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools exposing metadata comments. CREATE-style operations are not
 * exposed as tools and would be rejected by Oracle's least-privilege user.
 */
@Component
public class DdlTools {

	private final DdlService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the DDL service that assembles and executes COMMENT statements
	 */
	public DdlTools(DdlService service) {
		this.service = service;
	}

	/**
	 * Adds or replaces the comment on a table.
	 * <p>
	 * Only works for tables the MCP user owns (no system privilege exists for
	 * COMMENT on others' objects; you'll get ORA-01031 otherwise).
	 *
	 * @param schema  schema (owner) of the table
	 * @param table   table name
	 * @param comment comment text; single quotes are escaped automatically
	 * @return a confirmation message
	 */
	@McpTool(name = "comment_on_table",
			description = "Add or replace the comment on a table. Only works for tables the MCP user owns (no system privilege exists for COMMENT on others' objects; you'll get ORA-01031 otherwise).")
	public String commentOnTable(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Comment text. Single quotes are escaped automatically.", required = true) String comment) {
		return service.commentOnTable(schema, table, comment);
	}

	/**
	 * Adds or replaces the comment on a column.
	 * <p>
	 * Only works for tables the MCP user owns.
	 *
	 * @param schema  schema (owner) of the table
	 * @param table   table name
	 * @param column  column name
	 * @param comment comment text; single quotes are escaped automatically
	 * @return a confirmation message
	 */
	@McpTool(name = "comment_on_column",
			description = "Add or replace the comment on a column. Only works for tables the MCP user owns.")
	public String commentOnColumn(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Column name.", required = true) String column,
			@McpToolParam(description = "Comment text. Single quotes are escaped automatically.", required = true) String comment) {
		return service.commentOnColumn(schema, table, column, comment);
	}

	/**
	 * Creates or replaces a view in the MCP user's own schema.
	 * <p>
	 * Requires the {@code CREATE VIEW} privilege.
	 *
	 * @param schema      schema where the view will be created (typically the MCP user's own)
	 * @param view        view name
	 * @param selectBody  SELECT statement body for the view
	 * @return a confirmation message
	 */
	public String createOrReplaceView(
			@McpToolParam(description = "Schema (owner) for the new view (must be one the MCP user can create in, typically their own).", required = true) String schema,
			@McpToolParam(description = "View name.", required = true) String view,
			@McpToolParam(description = "SELECT statement body for the view (e.g. \"SELECT id, name FROM owner.table WHERE active = 1\").", required = true) String selectBody) {
		return service.createOrReplaceView(schema, view, selectBody);
	}

	/**
	 * Creates a private or public synonym pointing at another schema's object.
	 * <p>
	 * Requires {@code CREATE SYNONYM} (private) or {@code CREATE PUBLIC SYNONYM} (public).
	 *
	 * @param schema       schema where the synonym will live (ignored for PUBLIC)
	 * @param synonym      synonym name
	 * @param targetSchema schema of the target object
	 * @param targetName   name of the target object (table/view/sequence/procedure)
	 * @param isPublic     whether to create a PUBLIC synonym
	 * @return a confirmation message
	 */
	public String createSynonym(
			@McpToolParam(description = "Schema where the synonym will live (or any schema for PUBLIC).", required = true) String schema,
			@McpToolParam(description = "Synonym name.", required = true) String synonym,
			@McpToolParam(description = "Schema of the target object.", required = true) String targetSchema,
			@McpToolParam(description = "Name of the target object (table/view/sequence/procedure).", required = true) String targetName,
			@McpToolParam(description = "Whether to create a PUBLIC synonym (requires CREATE PUBLIC SYNONYM privilege).", required = false) Boolean isPublic) {
		return service.createSynonym(schema, synonym, targetSchema, targetName, Boolean.TRUE.equals(isPublic));
	}

	/**
	 * Creates a sequence in the MCP user's own schema.
	 * <p>
	 * All numeric options are optional. Requires the {@code CREATE SEQUENCE} privilege.
	 *
	 * @param schema     schema for the new sequence
	 * @param sequence   sequence name
	 * @param startWith  optional START WITH value; {@code null} omits the clause
	 * @param incrementBy optional INCREMENT BY value (can be negative for descending)
	 * @param cache      optional CACHE size; {@code null} or 0 means NOCACHE
	 * @param cycle      whether to CYCLE when max is reached
	 * @param order      whether to guarantee ORDER (useful in RAC)
	 * @return a confirmation message
	 */
	public String createSequence(
			@McpToolParam(description = "Schema (owner) for the new sequence.", required = true) String schema,
			@McpToolParam(description = "Sequence name.", required = true) String sequence,
			@McpToolParam(description = "Optional START WITH value.", required = false) Long startWith,
			@McpToolParam(description = "Optional INCREMENT BY value (can be negative for descending).", required = false) Long incrementBy,
			@McpToolParam(description = "Optional CACHE size (e.g. 20). 0 or omit means NOCACHE.", required = false) Long cache,
			@McpToolParam(description = "Whether to CYCLE when max is reached (default false).", required = false) Boolean cycle,
			@McpToolParam(description = "Whether to guarantee ORDER (default false, useful in RAC).", required = false) Boolean order) {
		return service.createSequence(schema, sequence, startWith, incrementBy, cache,
				Boolean.TRUE.equals(cycle), Boolean.TRUE.equals(order));
	}

	/**
	 * Creates an index on a table.
	 * <p>
	 * <strong>Warning:</strong> building an index on a large table can lock it
	 * (Standard Edition) and consumes space. Requires {@code CREATE ANY INDEX}
	 * if the table is in another schema, or table ownership.
	 *
	 * @param schema      schema where the index will live
	 * @param index       index name
	 * @param tableSchema schema of the target table
	 * @param table       target table name
	 * @param columns     column names to index (ordered)
	 * @param unique      whether to create a UNIQUE index
	 * @return a confirmation message
	 */
	public String createIndex(
			@McpToolParam(description = "Schema where the index will live.", required = true) String schema,
			@McpToolParam(description = "Index name.", required = true) String index,
			@McpToolParam(description = "Schema of the target table.", required = true) String tableSchema,
			@McpToolParam(description = "Target table name.", required = true) String table,
			@McpToolParam(description = "Column names to index (JSON array, ordered).", required = true) List<String> columns,
			@McpToolParam(description = "Whether to create a UNIQUE index (default false).", required = false) Boolean unique) {
		return service.createIndex(schema, index, tableSchema, table, columns, Boolean.TRUE.equals(unique));
	}

	/**
	 * Creates a materialized-view log on a table to enable fast refresh of
	 * materialized views.
	 * <p>
	 * Requires the {@code CREATE TABLE} privilege and quota on the master
	 * table's tablespace. Pick either PRIMARY KEY or ROWID (typically PRIMARY KEY).
	 *
	 * @param schema              schema (owner) of the master table
	 * @param table               master table name
	 * @param withRowid           whether to include ROWID columns
	 * @param withPrimaryKey      whether to include PRIMARY KEY columns (recommended)
	 * @param includingNewValues  whether to add INCLUDING NEW VALUES
	 * @return a confirmation message
	 */
	public String createMviewLog(
			@McpToolParam(description = "Schema (owner) of the master table.", required = true) String schema,
			@McpToolParam(description = "Master table name.", required = true) String table,
			@McpToolParam(description = "Whether to include ROWID columns.", required = false) Boolean withRowid,
			@McpToolParam(description = "Whether to include PRIMARY KEY columns (recommended).", required = false) Boolean withPrimaryKey,
			@McpToolParam(description = "Whether to add INCLUDING NEW VALUES (enables fast refresh with DML tracking).", required = false) Boolean includingNewValues) {
		return service.createMviewLog(schema, table,
				Boolean.TRUE.equals(withRowid),
				Boolean.TRUE.equals(withPrimaryKey),
				Boolean.TRUE.equals(includingNewValues));
	}
}
