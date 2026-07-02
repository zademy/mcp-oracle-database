package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.MetadataService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tools exposing read-only Oracle schema introspection.
 * All operations go through parameterised dictionary queries and never modify data.
 */
@Component
public class MetadataTools {

	private final MetadataService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the metadata service that queries {@code ALL_*} dictionary views
	 */
	public MetadataTools(MetadataService service) {
		this.service = service;
	}

	/**
	 * Lists all Oracle schemas (users) visible to the current connection.
	 *
	 * @return a sorted list of schema names
	 */
	@McpTool(name = "list_schemas",
			description = "List all Oracle schemas (users) visible to the current connection.")
	public List<String> listSchemas() {
		return service.listSchemas();
	}

	/**
	 * Lists tables, optionally filtered by schema and name pattern.
	 *
	 * @param schema      owner filter; {@code null} searches all schemas
	 * @param namePattern LIKE pattern for the table name (supports {@code %} and {@code _}); {@code null} defaults to {@code %}
	 * @return rows with {@code schema_name} and {@code table_name}
	 */
	@McpTool(name = "list_tables",
			description = "List tables. Supports an optional schema and a case-insensitive LIKE name pattern (use '%' and '_' wildcards; defaults to '%').")
	public List<Map<String, Object>> listTables(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "LIKE pattern for the table name, e.g. 'EMP%' or '%EMP%'. Defaults to '%'.", required = false) String namePattern) {
		return service.listTables(schema, namePattern);
	}

	/**
	 * Lists views, optionally filtered by schema and name pattern.
	 *
	 * @param schema      owner filter; {@code null} searches all schemas
	 * @param namePattern LIKE pattern for the view name; {@code null} defaults to {@code %}
	 * @return rows with {@code schema_name} and {@code view_name}
	 */
	@McpTool(name = "list_views",
			description = "List views. Optional schema and case-insensitive LIKE name pattern (defaults to '%').")
	public List<Map<String, Object>> listViews(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "LIKE pattern for the view name. Defaults to '%'.", required = false) String namePattern) {
		return service.listViews(schema, namePattern);
	}

	/**
	 * Describes the columns of a table: name, data type, nullability, default
	 * value, and comment.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows describing each column, ordered by position
	 */
	@McpTool(name = "describe_table",
			description = "Describe the columns of a table: name, data type, nullability, default value and comment. ALWAYS call this before writing a SELECT/INSERT/UPDATE against an unverified table, so column names and types are known rather than invented.")
	public List<Map<String, Object>> describeTable(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.describeTable(schema, table);
	}

	/**
	 * Lists indexes on a table with their columns, uniqueness, and status.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with index name, column list, uniqueness, and status
	 */
	@McpTool(name = "list_indexes",
			description = "List indexes on a table with their columns, uniqueness and status.")
	public List<Map<String, Object>> listIndexes(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listIndexes(schema, table);
	}

	/**
	 * Lists constraints on a table (primary key, foreign key, unique, check)
	 * with their columns and references.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with constraint type, columns, and any referenced constraint
	 */
	@McpTool(name = "list_constraints",
			description = "List constraints on a table (primary key, foreign key, unique, check) with their columns and references.")
	public List<Map<String, Object>> listConstraints(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listConstraints(schema, table);
	}

	/**
	 * Lists sequences with their range, increment, last number, and cycle flag.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each sequence
	 */
	@McpTool(name = "list_sequences",
			description = "List sequences with their range, increment, last number, cycle flag and cache size (queried from ALL_SEQUENCES, not DBA_SEQUENCES). cache_size > 0 means values are pre-allocated, so gaps can appear after a crash or rollback. For a single sequence's full metadata (including order_flag) use get_sequence_info. Optional schema filter.")
	public List<Map<String, Object>> listSequences(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listSequences(schema);
	}

	/**
	 * Returns metadata for a single sequence (range, increment, last number,
	 * cache size, cycle/order flags) without consuming NEXTVAL.
	 *
	 * @param schema   schema (owner) of the sequence
	 * @param sequence sequence name
	 * @return a single row describing the sequence, or a not-found message string
	 */
	@McpTool(name = "get_sequence_info",
			description = "Return metadata for a single sequence (min_value, max_value, increment_by, last_number, cache_size, cycle_flag, order_flag) from ALL_SEQUENCES. Read-only: does NOT consume NEXTVAL, so the sequence counter is untouched. Prefer this over querying ALL_SEQUENCES/DBA_SEQUENCES directly.")
	public Object getSequenceInfo(
			@McpToolParam(description = "Schema (owner) of the sequence.", required = true) String schema,
			@McpToolParam(description = "Sequence name.", required = true) String sequence) {
		Map<String, Object> info = service.getSequenceInfo(schema, sequence);
		return info == null ? "Sequence not found: " + schema + "." + sequence : info;
	}

	/**
	 * Quick boolean table-existence check over {@code ALL_TABLES} (views are
	 * not considered).
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return {@code true} if the table exists and is visible
	 */
	@McpTool(name = "table_exists",
			description = "Return true if a table exists (checked via ALL_TABLES; views are NOT considered). Use this instead of run_query {SELECT 1 FROM ALL_TABLES WHERE ...}.")
	public boolean tableExists(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.tableExists(schema, table);
	}

	/**
	 * Lists triggers with their type, triggering event, target table, and status.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each trigger
	 */
	@McpTool(name = "list_triggers",
			description = "List triggers with their type, triggering event, target table and status. Optional schema.")
	public List<Map<String, Object>> listTriggers(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listTriggers(schema);
	}

	/**
	 * Lists database objects (tables, views, packages, procedures, functions, ...).
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @param type   object-type filter (e.g. {@code "PROCEDURE"}, {@code "FUNCTION"}, {@code "PACKAGE"}, {@code "VIEW"}); {@code null} returns all types
	 * @return rows with owner, name, type, and status
	 */
	@McpTool(name = "list_objects",
			description = "List database objects (tables, views, packages, procedures, functions, ...). Optional schema and object type filter.")
	public List<Map<String, Object>> listObjects(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "Object type filter, e.g. 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'VIEW'.", required = false) String type) {
		return service.listObjects(schema, type);
	}

	/**
	 * Searches database objects by a case-insensitive LIKE name pattern.
	 *
	 * @param namePattern LIKE pattern for the object name
	 * @param type        optional object-type filter; {@code null} returns all types
	 * @return matching rows with owner, name, type, and status
	 */
	@McpTool(name = "search_objects",
			description = "Search database objects by a case-insensitive LIKE name pattern, optionally filtered by object type.")
	public List<Map<String, Object>> searchObjects(
			@McpToolParam(description = "LIKE pattern for the object name, e.g. 'EMP%' or '%ORDER%'.", required = true) String namePattern,
			@McpToolParam(description = "Optional object type filter, e.g. 'TABLE', 'VIEW'.", required = false) String type) {
		return service.searchObjects(namePattern, type);
	}

	/**
	 * Returns the DDL definition of an object as a CLOB string via
	 * {@code DBMS_METADATA.GET_DDL}. Read-only: never executes the DDL.
	 *
	 * @param schema schema (owner) of the object
	 * @param name   object name
	 * @param type   object type (e.g. {@code "TABLE"}, {@code "VIEW"}, {@code "INDEX"})
	 * @return the DDL script as a string
	 */
	@McpTool(name = "get_ddl",
			description = "Return the DDL definition of an object as a CLOB string, using DBMS_METADATA.GET_DDL. Read-only: never executes the DDL. All three parameters (schema, name, type) are REQUIRED. Valid type values: TABLE, VIEW, INDEX, SEQUENCE, PROCEDURE, FUNCTION, PACKAGE, PACKAGE BODY, TRIGGER, TYPE, SYNONYM, DB LINK.")
	public String getDdl(
			@McpToolParam(description = "Schema (owner) of the object.", required = true) String schema,
			@McpToolParam(description = "Object name.", required = true) String name,
			@McpToolParam(description = "Object type, e.g. 'TABLE', 'VIEW', 'INDEX', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'SEQUENCE', 'TRIGGER'.", required = true) String type) {
		return service.getDdl(schema, name, type);
	}

	/**
	 * Lists materialized views with their refresh mode, method, last refresh,
	 * and staleness.
	 *
	 * @param schema      owner filter; {@code null} searches all schemas
	 * @param namePattern LIKE pattern for the mview name; {@code null} defaults to {@code %}
	 * @return rows describing each materialized view
	 */
	@McpTool(name = "list_materialized_views",
			description = "List materialized views with their refresh mode, method, last refresh and staleness. Optional schema and LIKE name pattern.")
	public List<Map<String, Object>> listMaterializedViews(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "LIKE pattern for the mview name. Defaults to '%'.", required = false) String namePattern) {
		return service.listMaterializedViews(schema, namePattern);
	}

	/**
	 * Lists materialized-view logs defined on tables.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each mview log
	 */
	@McpTool(name = "list_mview_logs",
			description = "List materialized view logs defined on tables. Optional schema.")
	public List<Map<String, Object>> listMviewLogs(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listMviewLogs(schema);
	}

	/**
	 * Lists private and public synonyms with their resolved target owner/name
	 * and optional DB link.
	 *
	 * @param schema      owner filter; {@code null} searches all schemas
	 * @param namePattern LIKE pattern for the synonym name; {@code null} defaults to {@code %}
	 * @return rows with target owner, target name, and DB link
	 */
	@McpTool(name = "list_synonyms",
			description = "List private and public synonyms with their resolved target owner/name and optional DB link. Optional schema and LIKE name pattern.")
	public List<Map<String, Object>> listSynonyms(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "LIKE pattern for the synonym name. Defaults to '%'.", required = false) String namePattern) {
		return service.listSynonyms(schema, namePattern);
	}

	/**
	 * Lists partitions of a partitioned table.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with partition name, position, high value, row count, and compression
	 */
	@McpTool(name = "list_partitions",
			description = "List partitions of a partitioned table: name, position, high value, row count, compression, last analyzed.")
	public List<Map<String, Object>> listPartitions(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listPartitions(schema, table);
	}

	/**
	 * Lists subpartitions of a composite-partitioned table.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with subpartition name, position, high value, and row count
	 */
	@McpTool(name = "list_subpartitions",
			description = "List subpartitions of a composite-partitioned table. Requires schema and table.")
	public List<Map<String, Object>> listSubpartitions(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listSubpartitions(schema, table);
	}

	/**
	 * Lists partitions of indexes on a table.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with index name, partition, status, leaf blocks, distinct keys, and row count
	 */
	@McpTool(name = "list_ind_partitions",
			description = "List partitions of indexes on a table: index name, partition, status, leaf blocks, distinct keys, num rows.")
	public List<Map<String, Object>> listIndPartitions(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listIndPartitions(schema, table);
	}

	/**
	 * Lists database links visible to the current user.
	 *
	 * @return rows with owner, link name, connect username, host, and creation date
	 */
	@McpTool(name = "list_db_links",
			description = "List database links visible to the current user with their connect username, host and creation date.")
	public List<Map<String, Object>> listDbLinks() {
		return service.listDbLinks();
	}

	/**
	 * Lists stored procedures, functions, packages, and types.
	 *
	 * @param schema      owner filter; {@code null} searches all schemas
	 * @param namePattern LIKE pattern for the object name; {@code null} defaults to {@code %}
	 * @param type        object-type filter; {@code null} returns all PL/SQL types
	 * @return rows with status and last-DDL timestamp
	 */
	@McpTool(name = "list_procedures",
			description = "List stored procedures, functions, packages and types. Optional schema, LIKE name pattern and object type filter.")
	public List<Map<String, Object>> listProcedures(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "LIKE pattern for the object name. Defaults to '%'.", required = false) String namePattern,
			@McpToolParam(description = "Object type filter: 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TYPE', 'TYPE BODY'.", required = false) String type) {
		return service.listProcedures(schema, namePattern, type);
	}

	/**
	 * Lists user-defined Oracle types with their typecode, attribute count,
	 * methods, and supertype.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each type
	 */
	@McpTool(name = "list_types",
			description = "List user-defined Oracle types with their typecode, attribute count, methods and supertype.")
	public List<Map<String, Object>> listTypes(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listTypes(schema);
	}

	/**
	 * Lists objects that a given object depends on (referenced objects).
	 * <p>
	 * Useful for impact analysis before modifying code.
	 *
	 * @param schema schema (owner) of the object
	 * @param name   object name
	 * @param type   optional object-type filter; {@code null} returns all dependency types
	 * @return rows with referenced owner, name, type, and link name
	 */
	@McpTool(name = "list_dependencies",
			description = "List objects that a given object depends on (referenced objects). Useful for impact analysis before modifying code.")
	public List<Map<String, Object>> listDependencies(
			@McpToolParam(description = "Schema (owner) of the object.", required = true) String schema,
			@McpToolParam(description = "Object name.", required = true) String name,
			@McpToolParam(description = "Optional object type filter (e.g. 'VIEW', 'PROCEDURE').", required = false) String type) {
		return service.listDependencies(schema, name, type);
	}

	/**
	 * Lists objects whose status is INVALID (broken code needing recompile).
	 * Excludes index/LOB partitions.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @param type   optional object-type filter; {@code null} returns all types
	 * @return rows with status and last-DDL timestamp
	 */
	@McpTool(name = "list_invalid_objects",
			description = "List objects whose status is INVALID (broken code needing recompile). Excludes index/LOB partitions. Optional schema and type filter.")
	public List<Map<String, Object>> listInvalidObjects(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "Optional object type filter (e.g. 'PROCEDURE', 'TRIGGER').", required = false) String type) {
		return service.listInvalidObjects(schema, type);
	}

	/**
	 * Lists external tables with their access driver, default directory,
	 * reject limit, and access parameters.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each external table
	 */
	@McpTool(name = "list_external_tables",
			description = "List external tables with their access driver, default directory, reject limit and access parameters.")
	public List<Map<String, Object>> listExternalTables(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listExternalTables(schema);
	}

	/**
	 * Lists Oracle directory objects and their filesystem paths.
	 *
	 * @return rows with owner, directory name, and OS path
	 */
	@McpTool(name = "list_directories",
			description = "List Oracle directory objects and their filesystem paths.")
	public List<Map<String, Object>> listDirectories() {
		return service.listDirectories();
	}

	/**
	 * Lists LOB columns of a table with their segment, index, caching, logging,
	 * SECUREFILE flag, and retention.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows describing each LOB column
	 */
	@McpTool(name = "list_lob_columns",
			description = "List LOB columns of a table with their segment, index, caching, logging, SECUREFILE flag and retention.")
	public List<Map<String, Object>> listLobColumns(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.listLobColumns(schema, table);
	}

	/**
	 * Lists DBMS_SCHEDULER jobs with state, run/failure counts, last start,
	 * next run, and repeat interval.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @return rows describing each scheduler job
	 */
	@McpTool(name = "list_scheduler_jobs",
			description = "List DBMS_SCHEDULER jobs with state, run/failure counts, last start, next run and repeat interval.")
	public List<Map<String, Object>> listSchedulerJobs(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema) {
		return service.listSchedulerJobs(schema);
	}

	/**
	 * Lists recent DBMS_SCHEDULER job run details.
	 *
	 * @param schema  owner filter; {@code null} searches all schemas
	 * @param jobName optional job-name filter; {@code null} returns all jobs
	 * @param limit   maximum rows to return; {@code null} defaults to 20
	 * @return rows with operation, status, duration, error code, and additional info
	 */
	@McpTool(name = "list_scheduler_job_runs",
			description = "List recent DBMS_SCHEDULER job run details: operation, status, duration, error code, additional info. Ordered by log date desc.")
	public List<Map<String, Object>> listSchedulerJobRuns(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "Optional job name filter.", required = false) String jobName,
			@McpToolParam(description = "Maximum number of rows to return (default 20).", required = false) Integer limit) {
		return service.listSchedulerJobRuns(schema, jobName, limit == null ? 20 : limit);
	}

	/**
	 * Lists compilation errors of PL/SQL units.
	 * <p>
	 * Critical for diagnosing broken code.
	 *
	 * @param schema owner filter; {@code null} searches all schemas
	 * @param name   object-name filter; {@code null} returns all objects
	 * @param type   object-type filter; {@code null} returns all types
	 * @return rows with line, position, error text, and error number
	 */
	@McpTool(name = "get_plsql_errors",
			description = "List compilation errors of PL/SQL units (procedures, functions, packages, triggers, types). Optional schema, name and type filter. Critical for diagnosing broken code.")
	public List<Map<String, Object>> getPlsqlErrors(
			@McpToolParam(description = "Schema (owner) name. Omit to search all schemas.", required = false) String schema,
			@McpToolParam(description = "Object name filter.", required = false) String name,
			@McpToolParam(description = "Object type filter: 'PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY','TRIGGER','TYPE','TYPE BODY'.", required = false) String type) {
		return service.getPlsqlErrors(schema, name, type);
	}

	/**
	 * Returns optimizer statistics for a table.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @return rows with row count, blocks, avg row length, sample size, last analyzed, degree, and compression
	 */
	@McpTool(name = "get_table_stats",
			description = "Return optimizer statistics for a table: row count, blocks, avg row length, sample size, last analyzed, degree, partitioned, compression.")
	public List<Map<String, Object>> getTableStats(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table) {
		return service.getTableStats(schema, table);
	}

	/**
	 * Returns optimizer statistics for columns of a table.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @param column optional column-name filter; {@code null} returns all columns
	 * @return rows with NDV, density, null count, bucket count, low/high values, and histogram type
	 */
	@McpTool(name = "get_column_stats",
			description = "Return optimizer statistics for columns of a table: number of distinct values (NDV), density, null count, bucket count, low/high value, histogram type. Optional column filter.")
	public List<Map<String, Object>> getColumnStats(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Optional column name filter.", required = false) String column) {
		return service.getColumnStats(schema, table, column);
	}

	/**
	 * Returns the optimizer histogram endpoints for a column.
	 * <p>
	 * Useful to understand data skew and cardinality estimates.
	 *
	 * @param schema schema (owner) of the table
	 * @param table  table name
	 * @param column column name
	 * @return rows with endpoint number, endpoint value, and text representation
	 */
	@McpTool(name = "get_histogram",
			description = "Return the optimizer histogram endpoints for a column (frequency or height-balanced). Useful to understand data skew and cardinality estimates.")
	public List<Map<String, Object>> getHistogram(
			@McpToolParam(description = "Schema (owner) of the table.", required = true) String schema,
			@McpToolParam(description = "Table name.", required = true) String table,
			@McpToolParam(description = "Column name.", required = true) String column) {
		return service.getHistogram(schema, table, column);
	}

	/**
	 * Lists system privileges available to the current connection.
	 * <p>
	 * Includes directly granted system privs plus effective privs from enabled
	 * roles (deduplicated, ordered).
	 *
	 * @return rows with privilege name and admin-option flag
	 */
	@McpTool(name = "get_session_privs",
			description = "List system privileges available to the current connection: directly granted system privs plus effective privs from enabled roles (deduplicated, ordered). WHEN TO CALL: only when the user explicitly asks what privileges the connection has; do NOT call as a preamble before querying tables or objects.")
	public List<Map<String, Object>> getSessionPrivs() {
		return service.getSessionPrivs();
	}

	/**
	 * Lists roles enabled for the current connection.
	 *
	 * @return rows with role name, admin option, and default-role flag
	 */
	@McpTool(name = "get_session_roles",
			description = "List roles enabled for the current connection, with admin option and default-role flag. WHEN TO CALL: only when the user explicitly asks which roles the connection has; do NOT call as a preamble before querying tables or objects.")
	public List<Map<String, Object>> getSessionRoles() {
		return service.getSessionRoles();
	}
}
