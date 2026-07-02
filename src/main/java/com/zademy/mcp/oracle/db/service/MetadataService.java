package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Read-only schema introspection over Oracle's {@code ALL_*} dictionary views.
 * All queries are parameterised and only ever read — this service performs no
 * DDL or DML.
 */
@Service
public class MetadataService {

	private final OracleDataAccess db;

	/**
	 * Creates the metadata service, wiring the Oracle data-access wrapper.
	 *
	 * @param db the data-access facade used for every dictionary query
	 */
	public MetadataService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Lists every database schema (user) visible to the connection.
	 *
	 * @return a sorted list of schema names
	 */
	public List<String> listSchemas() {
		return db.queryForList("SELECT username FROM all_users ORDER BY username", Map.of())
				.stream()
				.map(row -> (String) row.get("username"))
				.toList();
	}

	/**
	 * Returns the current connected Oracle user name (the MCP least-privilege
	 * account). Useful for scope-limiting queries to the user's own schema in
	 * completion providers where no explicit schema is supplied.
	 *
	 * @return the current user/schema name, or an empty string on failure
	 */
	public String currentSchema() {
		try {
			return db.queryForObject(
					"SELECT SYS_CONTEXT('USERENV', 'CURRENT_USER') FROM dual", String.class);
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Lists tables visible to the current user, optionally filtered.
	 *
	 * @param schema     owner filter; {@code null} means all schemas
	 * @param namePattern substring filter on the table name; {@code null}/blank matches all
	 * @return rows with {@code schema_name} and {@code table_name}
	 */
	public List<Map<String, Object>> listTables(String schema, String namePattern) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, table_name
				FROM all_tables
				WHERE (:schema IS NULL OR owner = :schema)
				AND table_name LIKE :pattern ESCAPE '\\'
				ORDER BY owner, table_name
				""", BindParams.of("schema", schema, "pattern", pattern));
	}

	/**
	 * Lists views visible to the current user, optionally filtered.
	 *
	 * @param schema     owner filter; {@code null} means all schemas
	 * @param namePattern substring filter on the view name; {@code null}/blank matches all
	 * @return rows with {@code schema_name} and {@code view_name}
	 */
	public List<Map<String, Object>> listViews(String schema, String namePattern) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, view_name
				FROM all_views
				WHERE (:schema IS NULL OR owner = :schema)
				AND view_name LIKE :pattern ESCAPE '\\'
				ORDER BY owner, view_name
				""", BindParams.of("schema", schema, "pattern", pattern));
	}

	/**
	 * Describes every column of a table: data type, nullability, default, and
	 * any column comment.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows describing each column, ordered by position
	 */
	public List<Map<String, Object>> describeTable(String schema, String table) {
		return db.queryForList("""
				SELECT t.column_name,
				       t.data_type ||
				         CASE WHEN t.data_type IN ('CHAR','VARCHAR2','NCHAR','NVARCHAR2','RAW')
				              THEN '(' || t.data_length || ')'
				              WHEN t.data_precision IS NOT NULL
				              THEN '(' || t.data_precision || COALESCE(',' || NULLIF(t.data_scale, 0), '') || ')'
				              ELSE '' END AS data_type,
				       t.nullable,
				       t.data_default,
				       COALESCE(c.comments, '') AS comments,
				       t.column_id
				FROM all_tab_columns t
				LEFT JOIN all_col_comments c
				  ON c.owner = t.owner AND c.table_name = t.table_name AND c.column_name = t.column_name
				WHERE t.owner = :schema AND t.table_name = :table
				ORDER BY t.column_id
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists indexes defined on a table, including their columns and uniqueness.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with {@code index_name}, {@code columns}, {@code uniqueness}, and {@code status}
	 */
	public List<Map<String, Object>> listIndexes(String schema, String table) {
		return db.queryForList("""
				SELECT i.index_name,
				       (SELECT LISTAGG(ic.column_name, ', ') WITHIN GROUP (ORDER BY ic.column_position)
				        FROM all_ind_columns ic
				        WHERE ic.index_owner = i.owner AND ic.index_name = i.index_name) AS columns,
				       i.uniqueness,
				       i.status
				FROM all_indexes i
				WHERE i.table_owner = :schema AND i.table_name = :table
				ORDER BY i.index_name
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists constraints (PK, FK, unique, check) defined on a table.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with constraint type, columns, and any referenced constraint (for FKs)
	 */
	public List<Map<String, Object>> listConstraints(String schema, String table) {
		return db.queryForList("""
				SELECT c.constraint_name,
				       CASE c.constraint_type
				         WHEN 'P' THEN 'PRIMARY KEY'
				         WHEN 'R' THEN 'FOREIGN KEY'
				         WHEN 'U' THEN 'UNIQUE'
				         WHEN 'C' THEN 'CHECK / NOT NULL'
				         WHEN 'O' THEN 'READ ONLY'
				         ELSE c.constraint_type END AS constraint_type,
				       (SELECT LISTAGG(cc.column_name, ', ') WITHIN GROUP (ORDER BY cc.position)
				        FROM all_cons_columns cc
				        WHERE cc.owner = c.owner AND cc.constraint_name = c.constraint_name) AS columns,
				       c.r_owner AS referenced_owner,
				       c.r_constraint_name AS referenced_constraint
				FROM all_constraints c
				WHERE c.owner = :schema AND c.table_name = :table
				ORDER BY c.constraint_type, c.constraint_name
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists sequences visible to the current user with their range, increment,
	 * and last-allocated number.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows describing each sequence
	 */
	public List<Map<String, Object>> listSequences(String schema) {
		return db.queryForList("""
				SELECT sequence_owner AS schema_name,
				       sequence_name,
				       min_value,
				       max_value,
				       increment_by,
				       last_number,
				       cycle_flag,
				       cache_size
				FROM all_sequences
				WHERE (:schema IS NULL OR sequence_owner = :schema)
				ORDER BY sequence_owner, sequence_name
				""", BindParams.of("schema", schema));
	}

	/**
	 * Returns metadata for a single sequence (range, increment, last number,
	 * cache size, cycle and order flags) from {@code all_sequences}. This is a
	 * pure read — it does <strong>not</strong> consume {@code NEXTVAL}, so the
	 * sequence counter is untouched. Prefer this over hand-written queries on
	 * {@code ALL_SEQUENCES}/{@code DBA_SEQUENCES}.
	 *
	 * @param schema   owner of the sequence
	 * @param sequence sequence name
	 * @return a single row describing the sequence, or {@code null} if not found
	 */
	public Map<String, Object> getSequenceInfo(String schema, String sequence) {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT sequence_owner AS schema_name,
				       sequence_name,
				       min_value,
				       max_value,
				       increment_by,
				       last_number,
				       cycle_flag,
				       cache_size,
				       order_flag
				FROM all_sequences
				WHERE sequence_owner = :schema AND sequence_name = :sequence
				""", Map.of("schema", schema, "sequence", sequence));
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Quick existence check for a table via {@code all_tables}. Equivalent to a
	 * boolean {@code SELECT 1 FROM all_tables WHERE owner = :schema AND
	 * table_name = :table}, but returns the flag directly. Checks tables only
	 * (not views); for views use {@link #listViews}.
	 *
	 * @param schema owner of the table
	 * @param table  table name
	 * @return {@code true} if the table exists and is visible to the connection
	 */
	public boolean tableExists(String schema, String table) {
		Long count = db.queryForObject("""
				SELECT COUNT(*) FROM all_tables
				WHERE owner = :schema AND table_name = :table
				""", Map.of("schema", schema, "table", table), Long.class);
		return count != null && count > 0;
	}

	/**
	 * Lists triggers visible to the current user.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows with trigger type, event, target table, and status
	 */
	public List<Map<String, Object>> listTriggers(String schema) {
		return db.queryForList("""
				SELECT owner AS schema_name, trigger_name, trigger_type, triggering_event, table_owner, table_name, status
				FROM all_triggers
				WHERE (:schema IS NULL OR owner = :schema)
				ORDER BY owner, trigger_name
				""", BindParams.of("schema", schema));
	}

	/**
	 * Lists objects visible to the current user, optionally filtered by schema
	 * and object type.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @param type   object-type filter (e.g. {@code "TABLE"}, {@code "VIEW"}); {@code null} means all types
	 * @return rows with owner, name, type, and status
	 */
	public List<Map<String, Object>> listObjects(String schema, String type) {
		return db.queryForList("""
				SELECT owner AS schema_name, object_name, object_type, status
				FROM all_objects
				WHERE (:schema IS NULL OR owner = :schema)
				AND (:type IS NULL OR object_type = :type)
				ORDER BY owner, object_type, object_name
				""", BindParams.of("schema", schema, "type", type));
	}

	/**
	 * Searches for objects by name pattern across all visible schemas.
	 *
	 * @param namePattern substring to match against the object name
	 * @param type        object-type filter; {@code null} means all types
	 * @return matching rows with owner, name, type, and status
	 */
	public List<Map<String, Object>> searchObjects(String namePattern, String type) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, object_name, object_type, status
				FROM all_objects
				WHERE object_name LIKE :pattern ESCAPE '\\'
				AND (:type IS NULL OR object_type = :type)
				ORDER BY owner, object_type, object_name
				""", BindParams.of("pattern", pattern, "type", type));
	}

	/**
	 * Returns the DDL text of an object via {@code DBMS_METADATA} (read-only).
	 *
	 * @param schema the owner of the object
	 * @param name   the object name
	 * @param type   the object type (e.g. {@code "TABLE"}, {@code "INDEX"})
	 * @return the DDL script as a single string
	 */
	public String getDdl(String schema, String name, String type) {
		return db.queryForObject("""
				SELECT DBMS_METADATA.GET_DDL(:type, :name, :schema)
				FROM dual
				""", BindParams.of("schema", schema, "name", name, "type", type), String.class);
	}

	/**
	 * Returns the DDL for an object, auto-detecting its type from
	 * {@code all_objects}.
	 *
	 * <p>Convenience overload for MCP resources where the client does not
	 * know (or need to specify) the Oracle object type. The first matching
	 * non-partition type is used.
	 *
	 * @param schema owner of the object
	 * @param name   object name
	 * @return the DDL text, or {@code null} if the object is not found
	 */
	public String getDdlAuto(String schema, String name) {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT object_type
				FROM all_objects
				WHERE owner = :schema AND object_name = :name
				AND object_type NOT IN ('INDEX PARTITION','TABLE PARTITION','LOB','TABLE SUBPARTITION','INDEX SUBPARTITION')
				ORDER BY object_type
				FETCH FIRST 1 ROWS ONLY
				""", Map.of("schema", schema, "name", name));
		if (rows.isEmpty()) {
			return null;
		}
		String type = String.valueOf(rows.get(0).get("object_type"));
		return getDdl(schema, name, type);
	}

	/**
	 * Lists materialized views with their refresh mode, method, and staleness.
	 *
	 * @param schema     owner filter; {@code null} means all schemas
	 * @param namePattern substring filter on the mview name
	 * @return rows describing each materialized view
	 */
	public List<Map<String, Object>> listMaterializedViews(String schema, String namePattern) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, mview_name AS name, container_name,
				       refresh_mode, refresh_method, last_refresh_type, last_refresh_date,
				       compile_state, staleness, use_no_index
				FROM all_mviews
				WHERE (:schema IS NULL OR owner = :schema)
				AND mview_name LIKE :pattern ESCAPE '\\'
				ORDER BY owner, mview_name
				""", BindParams.of("schema", schema, "pattern", pattern));
	}

	/**
	 * Lists materialized-view log definitions.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows describing each mview log
	 */
	public List<Map<String, Object>> listMviewLogs(String schema) {
		return db.queryForList("""
				SELECT owner AS schema_name, master AS table_name, log AS log_name,
				       rowids, primary_key, sequence, include_new_values, purge_asynchronous
				FROM all_mview_logs
				WHERE (:schema IS NULL OR owner = :schema)
				ORDER BY owner, master
				""", BindParams.of("schema", schema));
	}

	/**
	 * Lists synonyms visible to the current user.
	 *
	 * @param schema     owner filter; {@code null} means all schemas
	 * @param namePattern substring filter on the synonym name
	 * @return rows with target owner, target name, and optional DB link
	 */
	public List<Map<String, Object>> listSynonyms(String schema, String namePattern) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, synonym_name AS name,
				       table_owner AS target_owner, table_name AS target_name, db_link
				FROM all_synonyms
				WHERE (:schema IS NULL OR owner = :schema)
				AND synonym_name LIKE :pattern ESCAPE '\\'
				ORDER BY owner, synonym_name
				""", BindParams.of("schema", schema, "pattern", pattern));
	}

	/**
	 * Lists partitions of a partitioned table.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with partition position, high value, row count, and compression
	 */
	public List<Map<String, Object>> listPartitions(String schema, String table) {
		return db.queryForList("""
				SELECT table_owner AS schema_name, table_name, partition_name,
				       partition_position, high_value, num_rows, blocks, compression,
				       last_analyzed, composite, subpartition_count
				FROM all_tab_partitions
				WHERE table_owner = :schema AND table_name = :table
				ORDER BY partition_position
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists subpartitions of a composite-partitioned table.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with subpartition position, high value, and row count
	 */
	public List<Map<String, Object>> listSubpartitions(String schema, String table) {
		return db.queryForList("""
				SELECT table_owner AS schema_name, table_name, partition_name,
				       subpartition_name, subpartition_position, high_value,
				       num_rows, blocks, last_analyzed
				FROM all_tab_subpartitions
				WHERE table_owner = :schema AND table_name = :table
				ORDER BY partition_name, subpartition_position
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists index partitions for a table's indexes.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with partition status, leaf blocks, distinct keys, and clustering factor
	 */
	public List<Map<String, Object>> listIndPartitions(String schema, String table) {
		return db.queryForList("""
				SELECT ip.index_owner AS schema_name, ip.index_name, ip.partition_name,
				       ip.partition_position, ip.status, ip.leaf_blocks, ip.distinct_keys,
				       ip.clustering_factor, ip.num_rows, ip.last_analyzed
				FROM all_ind_partitions ip
				JOIN all_indexes i
				  ON i.owner = ip.index_owner AND i.index_name = ip.index_name
				WHERE i.table_owner = :schema AND i.table_name = :table
				ORDER BY ip.index_name, ip.partition_position
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists database links visible to the current user.
	 *
	 * @return rows with owner, link name, username, and host
	 */
	public List<Map<String, Object>> listDbLinks() {
		return db.queryForList("""
				SELECT owner AS schema_name, db_link AS name, username, host, created
				FROM all_db_links
				ORDER BY owner, db_link
				""", Map.of());
	}

	/**
	 * Lists PL/SQL program units (procedures, functions, packages, types).
	 *
	 * @param schema     owner filter; {@code null} means all schemas
	 * @param namePattern substring filter on the object name
	 * @param type        object-type filter; {@code null} means all PL/SQL types
	 * @return rows with status and last-DDL timestamp
	 */
	public List<Map<String, Object>> listProcedures(String schema, String namePattern, String type) {
		String pattern = likePattern(namePattern);
		return db.queryForList("""
				SELECT owner AS schema_name, object_name AS name, object_type AS type, status, created, last_ddl_time
				FROM all_objects
				WHERE object_type IN ('PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY','TYPE','TYPE BODY')
				AND (:schema IS NULL OR owner = :schema)
				AND (:type IS NULL OR object_type = :type)
				AND object_name LIKE :pattern ESCAPE '\\'
				ORDER BY owner, object_type, object_name
				""", BindParams.of("schema", schema, "type", type, "pattern", pattern));
	}

	/**
	 * Lists user-defined types visible to the current user.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows with typecode, attribute count, and supertype info
	 */
	public List<Map<String, Object>> listTypes(String schema) {
		return db.queryForList("""
				SELECT owner AS schema_name, type_name AS name, typecode,
				       attributes, methods, final, instantiable,
				       supertype_owner, supertype_name
				FROM all_types
				WHERE (:schema IS NULL OR owner = :schema)
				ORDER BY owner, type_name
				""", BindParams.of("schema", schema));
	}

	/**
	 * Lists objects on which the given object depends.
	 *
	 * @param schema the owner of the object
	 * @param name   the object name
	 * @param type   object-type filter; {@code null} means all dependency types
	 * @return rows with referenced owner, name, type, and link name
	 */
	public List<Map<String, Object>> listDependencies(String schema, String name, String type) {
		return db.queryForList("""
				SELECT owner, name, type,
				       referenced_owner, referenced_name, referenced_type, referenced_link_name,
				       dependency_type
				FROM all_dependencies
				WHERE owner = :schema AND name = :name
				AND (:type IS NULL OR type = :type)
				ORDER BY referenced_owner, referenced_name, referenced_type
				""", BindParams.of("schema", schema, "name", name, "type", type));
	}

	/**
	 * Lists invalid (non-compiling) objects, excluding partition sub-objects.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @param type   object-type filter; {@code null} means all types
	 * @return rows with status and last-DDL timestamp
	 */
	public List<Map<String, Object>> listInvalidObjects(String schema, String type) {
		return db.queryForList("""
				SELECT owner AS schema_name, object_name AS name, object_type AS type, status, last_ddl_time
				FROM all_objects
				WHERE status = 'INVALID'
				AND object_type NOT IN ('INDEX','INDEX PARTITION','LOB','TABLE PARTITION','TABLE SUBPARTITION')
				AND (:schema IS NULL OR owner = :schema)
				AND (:type IS NULL OR object_type = :type)
				ORDER BY owner, object_type, object_name
				""", BindParams.of("schema", schema, "type", type));
	}

	/**
	 * Lists external tables visible to the current user.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows with driver type, directory, reject limit, and access parameters
	 */
	public List<Map<String, Object>> listExternalTables(String schema) {
		return db.queryForList("""
				SELECT owner AS schema_name, table_name AS name, type_owner, type_name,
				       default_directory_name AS directory, reject_limit, access_type,
				       access_parameters, property
				FROM all_external_tables
				WHERE (:schema IS NULL OR owner = :schema)
				ORDER BY owner, table_name
				""", BindParams.of("schema", schema));
	}

	/**
	 * Lists directory objects visible to the current user.
	 *
	 * @return rows with owner, directory name, and OS path
	 */
	public List<Map<String, Object>> listDirectories() {
		return db.queryForList("""
				SELECT owner AS schema_name, directory_name AS name, directory_path
				FROM all_directories
				ORDER BY owner, directory_name
				""", Map.of());
	}

	/**
	 * Lists LOB columns of a table.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with segment name, index, cache, logging, and SecureFile flags
	 */
	public List<Map<String, Object>> listLobColumns(String schema, String table) {
		return db.queryForList("""
				SELECT owner AS schema_name, table_name AS name, column_name, segment_name,
				       index_name, cache, logging, in_row, securefile, partitioned, retention
				FROM all_lobs
				WHERE owner = :schema AND table_name = :table
				ORDER BY column_name
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Lists scheduler jobs visible to the current user.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @return rows with job type, enabled flag, state, run count, and next-run date
	 */
	public List<Map<String, Object>> listSchedulerJobs(String schema) {
		return db.queryForList("""
				SELECT owner AS schema_name, job_name AS name, job_type, job_action,
				       enabled, state, run_count, failure_count, last_start_date,
				       next_run_date, repeat_interval, job_class
				FROM all_scheduler_jobs
				WHERE (:schema IS NULL OR owner = :schema)
				ORDER BY owner, job_name
				""", BindParams.of("schema", schema));
	}

	/**
	 * Lists recent scheduler-job run details.
	 *
	 * @param schema  owner filter; {@code null} means all schemas
	 * @param jobName job-name filter; {@code null} means all jobs
	 * @param limit   maximum number of rows to return (most recent first)
	 * @return rows with status, run duration, start date, and error code
	 */
	public List<Map<String, Object>> listSchedulerJobRuns(String schema, String jobName, int limit) {
		return db.queryForList("""
				SELECT owner AS schema_name, job_name AS name, job_subname,
				       log_date, operation, status, run_duration, actual_start_date,
				       instance_id, session_id, error# AS error_code, additional_info
				FROM all_scheduler_job_run_details
				WHERE (:schema IS NULL OR owner = :schema)
				AND (:jobName IS NULL OR job_name = :jobName)
				ORDER BY log_date DESC
				FETCH FIRST :limit ROWS ONLY
				""", BindParams.of("schema", schema, "jobName", jobName, "limit", limit));
	}

	/**
	 * Retrieves compilation errors for PL/SQL objects.
	 *
	 * @param schema owner filter; {@code null} means all schemas
	 * @param name   object-name filter; {@code null} means all objects
	 * @param type   object-type filter; {@code null} means all types
	 * @return rows with line, position, error text, and error number
	 */
	public List<Map<String, Object>> getPlsqlErrors(String schema, String name, String type) {
		return db.queryForList("""
				SELECT owner AS schema_name, name, type, sequence# AS seq, line, position AS pos,
				       text, attribute, error_number AS error_num
				FROM all_errors
				WHERE (:schema IS NULL OR owner = :schema)
				AND (:name IS NULL OR name = :name)
				AND (:type IS NULL OR type = :type)
				ORDER BY name, type, sequence#
				""", BindParams.of("schema", schema, "name", name, "type", type));
	}

	/**
	 * Returns table-level statistics (row count, blocks, chain count, etc.).
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @return rows with optimizer statistics and storage metadata
	 */
	public List<Map<String, Object>> getTableStats(String schema, String table) {
		return db.queryForList("""
				SELECT owner AS schema_name, table_name AS name,
				       num_rows, blocks, empty_blocks, avg_space, chain_cnt,
				       avg_row_len, sample_size, last_analyzed, degree, instances,
				       partitioned, logging, compression, cache
				FROM all_tables
				WHERE owner = :schema AND table_name = :table
				""", Map.of("schema", schema, "table", table));
	}

	/**
	 * Returns column-level statistics (NDV, density, histogram info).
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @param column column filter; {@code null} means all columns
	 * @return rows with NDV, density, null count, low/high values, and histogram type
	 */
	public List<Map<String, Object>> getColumnStats(String schema, String table, String column) {
		return db.queryForList("""
				SELECT owner AS schema_name, table_name AS name, column_name,
				       num_distinct AS ndv, density, num_nulls, num_buckets,
				       low_value, high_value, sample_size, last_analyzed,
				       histogram, global_stats, user_stats
				FROM all_tab_col_statistics
				WHERE owner = :schema AND table_name = :table
				AND (:column IS NULL OR column_name = :column)
				ORDER BY column_name
				""", BindParams.of("schema", schema, "table", table, "column", column));
	}

	/**
	 * Returns histogram buckets for a specific column.
	 *
	 * @param schema the owner of the table
	 * @param table  the table name
	 * @param column the column name
	 * @return rows with endpoint number, endpoint value, and text representation
	 */
	public List<Map<String, Object>> getHistogram(String schema, String table, String column) {
		return db.queryForList("""
				SELECT owner AS schema_name, table_name AS name, column_name,
				       endpoint_number, endpoint_value,
				       SUBSTR(endpoint_actual_value, 1, 200) AS endpoint_value_text
				FROM all_histograms
				WHERE owner = :schema AND table_name = :table AND column_name = :column
				ORDER BY endpoint_number
				""", Map.of("schema", schema, "table", table, "column", column));
	}

	/**
	 * Lists system privileges granted directly to the session user.
	 *
	 * @return rows with privilege name and admin-option flag
	 */
	public List<Map<String, Object>> getSessionPrivs() {
		return db.queryForList("""
				SELECT username, privilege, admin_option
				FROM user_sys_privs
				UNION ALL
				SELECT NULL, privilege, NULL
				FROM session_privs
				WHERE privilege NOT IN (SELECT privilege FROM user_sys_privs)
				ORDER BY privilege
				""", Map.of());
	}

	/**
	 * Lists roles currently enabled in the session, enriched with the
	 * admin-option and default-role flags from {@code USER_ROLE_PRIVS}.
	 *
	 * <p>{@code SESSION_ROLES} exposes only a single {@code ROLE} column, so the
	 * flags are recovered via a left join against {@code USER_ROLE_PRIVS}.
	 * {@code os_granted} is intentionally omitted: it lives only in
	 * {@code DBA_ROLE_PRIVS} and is not meaningful for a least-privilege MCP
	 * user. This keeps the query free of any {@code SELECT_CATALOG_ROLE}
	 * dependency and works unchanged on Oracle 19c.
	 *
	 * @return rows with role name, admin option, and default-role flag
	 */
	public List<Map<String, Object>> getSessionRoles() {
		return db.queryForList("""
				SELECT s.role,
				       r.admin_option,
				       r.default_role
				FROM   session_roles s
				LEFT JOIN user_role_privs r ON r.granted_role = s.role
				ORDER BY s.role
				""", Map.of());
	}

	/**
	 * Convert a user-supplied substring into a safe LIKE pattern. {@code null}/blank
	 * means "match all"; {@code %} and {@code _} are honoured when the caller passes
	 * them, otherwise the substring is wrapped.
	 *
	 * @param namePattern the raw substring from the caller
	 * @return a LIKE pattern suitable for Oracle's {@code LIKE} operator
	 */
	private static String likePattern(String namePattern) {
		if (namePattern == null || namePattern.isBlank()) {
			return "%";
		}
		if (namePattern.indexOf('%') >= 0 || namePattern.indexOf('_') >= 0) {
			return namePattern.toUpperCase();
		}
		return "%" + namePattern.toUpperCase() + "%";
	}
}
