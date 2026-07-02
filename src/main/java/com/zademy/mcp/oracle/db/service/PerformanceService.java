package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Read-only performance diagnostics over Oracle's {@code V$} dynamic
 * performance views. Requires {@code SELECT_CATALOG_ROLE} (already granted to
 * the least-privilege user). All queries are parameterised; this service never
 * modifies state.
 */
@Service
public class PerformanceService {

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer (named-parameter path is used throughout)
	 */
	public PerformanceService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Lists currently active sessions from {@code V$SESSION}.
	 *
	 * @param username optional Oracle username filter; {@code null} returns all active sessions
	 * @param sid      optional session id filter; {@code null} returns all active sessions
	 * @return one row per active session (sid, serial, username, machine, program,
	 *         wait event, seconds since last call, etc.)
	 */
	public List<Map<String, Object>> listActiveSessions(String username, Integer sid) {
		return db.queryForList("""
				SELECT sid, serial# AS serial_num, username, machine, terminal, program,
				       status, server, schemaname, osuser, process, logon_time,
				       last_call_et AS seconds_since_last_call, module, action, client_info
				FROM v$session
				WHERE status = 'ACTIVE'
				AND (:username IS NULL OR username = :username)
				AND (:sid IS NULL OR sid = :sid)
				ORDER BY sid
				""", BindParams.of("username", username, "sid", sid));
	}

	/**
	 * Lists sessions currently blocked by another session, joining
	 * {@code V$SESSION} to itself to surface the blocker.
	 *
	 * @return one row per blocked session (sid, blocker sid, blocker username,
	 *         wait event, seconds in wait)
	 */
	public List<Map<String, Object>> listBlockedSessions() {
		return db.queryForList("""
				SELECT s.sid, s.serial# AS serial_num, s.username, s.machine, s.program,
				       s.status, s.blocking_session AS blocker_sid,
				       bs.username AS blocker_username, bs.program AS blocker_program,
				       s.event AS wait_event, s.seconds_in_wait
				FROM v$session s
				LEFT JOIN v$session bs ON bs.sid = s.blocking_session
				WHERE s.blocking_session IS NOT NULL
				ORDER BY s.sid
				""", Map.of());
	}

	/**
	 * Lists current DML/transaction/media-recovery locks from {@code V$LOCK}
	 * joined with {@code V$SESSION} and {@code V$LOCKED_OBJECT}.
	 *
	 * @return one row per lock with holder sid, locked object, lock type
	 *         (decoded), held mode and requested mode
	 */
	public List<Map<String, Object>> listLocks() {
		return db.queryForList("""
				SELECT s.sid, s.serial# AS serial_num, s.username, s.machine,
				       lo.oracle_username AS locked_user, o.owner AS object_owner,
				       o.object_name, o.object_type,
				       DECODE(l.type,
				              'TM','DML','TX','Transaction','UL','User','MR','Media Recovery',
				              'ST','Disk Space','CU','Cursor Bind','JX','Task Execution',
				              l.type) AS lock_type,
				       DECODE(l.lmode,0,'None',1,'Null',2,'Row Share',3,'Row Exclusive',
				              4,'Share',5,'Share Row Exclusive',6,'Exclusive',TO_CHAR(l.lmode)) AS held_mode,
				       DECODE(l.request,0,'None',1,'Null',2,'Row Share',3,'Row Exclusive',
				              4,'Share',5,'Share Row Exclusive',6,'Exclusive',TO_CHAR(l.request)) AS requested_mode,
				       l.ctime AS seconds_held
				FROM v$lock l
				JOIN v$session s ON s.sid = l.sid
				LEFT JOIN v$locked_object lo ON lo.session_id = s.sid
				LEFT JOIN all_objects o ON o.object_id = lo.object_id
				WHERE l.type IN ('TM','TX','UL','MR','ST','CU','JX')
				AND (l.lmode > 0 OR l.request > 0)
				ORDER BY l.sid, l.type
				""", Map.of());
	}

	/**
	 * Reconstructs the full SQL text currently being executed by a session.
	 *
	 * @param sid       the session id
	 * @param serialNum the session serial number (used to disambiguate reused sids)
	 * @return the concatenated SQL text, or an empty string if no text is found
	 */
	public String getSessionSqlText(int sid, int serialNum) {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT sql_text
				FROM v$sqltext_with_newlines
				WHERE address = (SELECT sql_address FROM v$session WHERE sid = :sid AND serial# = :serial)
				AND hash_value = (SELECT sql_hash_value FROM v$session WHERE sid = :sid AND serial# = :serial)
				ORDER BY piece
				""", Map.of("sid", sid, "serial", serialNum));
		StringBuilder sb = new StringBuilder();
		for (Map<String, Object> row : rows) {
			Object text = row.get("sql_text");
			if (text != null) {
				sb.append(text);
			}
		}
		return sb.toString();
	}

	/**
	 * Lists the top-N SQL statements in the shared pool, ranked by the chosen metric.
	 *
	 * @param orderBy ranking metric: {@code "buffer_gets"} (default),
	 *                {@code "disk_reads"/"disk"}, {@code "elapsed"/"elapsed_time"},
	 *                {@code "executions"/"exec"}, or {@code "cpu_time"/"cpu"}.
	 *                Unknown values fall back to {@code buffer_gets}.
	 * @param limit   maximum number of rows returned
	 * @return one row per SQL (sql_id, executions, buffer gets, disk reads,
	 *         elapsed/cpu time, 200-char preview of SQL text)
	 */
	public List<Map<String, Object>> listTopSql(String orderBy, int limit) {
		String safe = switch (orderBy == null ? "" : orderBy.toLowerCase()) {
			case "disk_reads", "disk" -> "disk_reads";
			case "elapsed", "elapsed_time" -> "elapsed_time";
			case "executions", "exec" -> "executions";
			case "cpu_time", "cpu" -> "cpu_time";
			default -> "buffer_gets";
		};
		String sql = """
				SELECT sql_id, child_number AS child, parsing_schema_name AS parsed_by,
				       executions, buffer_gets, disk_reads, rows_processed,
				       elapsed_time / 1000 AS elapsed_ms, cpu_time / 1000 AS cpu_ms,
				       elapsed_time / NULLIF(executions, 0) / 1000 AS avg_elapsed_ms,
				       SUBSTR(sql_text, 1, 200) AS sql_preview
				FROM v$sql
				WHERE executions > 0
				ORDER BY %s DESC
				FETCH FIRST :limit ROWS ONLY
				""".formatted(safe);
		return db.queryForList(sql, Map.of("limit", limit));
	}

	/**
	 * Lists current wait events from {@code V$SESSION_WAIT}.
	 *
	 * @param sid       optional session id filter; {@code null} returns all sessions
	 * @param waitClass optional wait class filter (e.g. {@code "User I/O"},
	 *                  {@code "Concurrency"}); {@code null} returns all classes
	 * @return one row per wait event (sid, event name, p1/p2/p3 parameters,
	 *         wait class, time in wait)
	 */
	public List<Map<String, Object>> getWaitEvents(Integer sid, String waitClass) {
		return db.queryForList("""
				SELECT sid, seq#, event, p1text, p1, p2text, p2, p3text, p3,
				       wait_class, wait_time, seconds_in_wait, state
				FROM v$session_wait
				WHERE (:sid IS NULL OR sid = :sid)
				AND (:waitClass IS NULL OR wait_class = :waitClass)
				ORDER BY sid
				""", BindParams.of("sid", sid, "waitClass", waitClass));
	}

	/**
	 * Lists long-running operations currently tracked in {@code V$SESSION_LONGOPS}.
	 * Only operations still in progress ({@code sofar < totalwork}) are returned.
	 *
	 * @param sid optional session id filter; {@code null} returns all sessions
	 * @return one row per operation (opname, target, percent done, time remaining)
	 */
	public List<Map<String, Object>> getSessionLongops(Integer sid) {
		return db.queryForList("""
				SELECT sid, serial# AS serial_num, opname, target, schemaname,
				       sofar, totalwork, units, TO_CHAR(ROUND((sofar / NULLIF(totalwork,0)) * 100, 2)) AS pct_done,
				       start_time, last_update_time, time_remaining AS seconds_remaining, elapsed_seconds,
				       message, username
				FROM v$session_longops
				WHERE (:sid IS NULL OR sid = :sid)
				AND sofar < totalwork
				ORDER BY start_time DESC
				""", BindParams.of("sid", sid));
	}

	/**
	 * Returns instance-level information from {@code V$INSTANCE} (name, host,
	 * version, startup time, status, archiver, database status).
	 *
	 * @return a single-row map, or an empty map if the view returns no rows
	 */
	public Map<String, Object> getInstanceInfo() {
		return db.queryForList("""
				SELECT instance_name, host_name, version, startup_time, status,
				       parallel, thread# AS thread, archiver, logins, shutdown_pending, database_status
				FROM v$instance
				""", Map.of()).stream().findFirst().orElse(Map.of());
	}

	/**
	 * Returns database-level information from {@code V$DATABASE} (name, creation
	 * time, log mode, open mode, role, force logging, flashback status).
	 *
	 * @return a single-row map, or an empty map if the view returns no rows
	 */
	public Map<String, Object> getDatabaseInfo() {
		return db.queryForList("""
				SELECT name, created, log_mode, open_mode, dbid,
				       platform_id, platform_name, role, force_logging, flashback_on
				FROM v$database
				""", Map.of()).stream().findFirst().orElse(Map.of());
	}

	/**
	 * Lists system-wide statistics from {@code V$SYSSTAT} (logical reads, physical
	 * reads, sorts, commits, etc.).
	 *
	 * @return one row per statistic, ordered by class then name
	 */
	public List<Map<String, Object>> getSystemStats() {
		return db.queryForList("""
				SELECT statistic# AS stat_id, name, class, value
				FROM v$sysstat
				WHERE class IN (1, 2, 4, 8, 16, 32, 64, 128)
				ORDER BY class, name
				""", Map.of());
	}

	/**
	 * Lists system time-model statistics from {@code V$SYS_TIME_MODEL} (DB time,
	 * DB CPU, parse time elapsed, etc.).
	 *
	 * @return one row per statistic, ordered by name
	 */
	public List<Map<String, Object>> getDbTimeModel() {
		return db.queryForList("""
				SELECT stat_id, stat_name AS name, value
				FROM v$sys_time_model
				ORDER BY stat_name
				""", Map.of());
	}

	/**
	 * Lists init parameters from {@code V$PARAMETER} with metadata about whether
	 * they are modifiable at session/system level.
	 *
	 * @param name optional name filter (supports SQL wildcards via LIKE; case-insensitive);
	 *             {@code null}/{@code blank} returns all parameters
	 * @return one row per parameter (name, type, value, description, modifiability)
	 */
	public List<Map<String, Object>> getParameter(String name) {
		return db.queryForList("""
				SELECT name, type, value, display_value, description,
				       isses_modifiable AS session_modifiable,
				       issys_modifiable AS system_modifiable,
				       isdefault, ismodified
				FROM v$parameter
				WHERE (:name IS NULL OR UPPER(name) LIKE UPPER(:name) ESCAPE '\\')
				ORDER BY name
				""", BindParams.of("name", name));
	}

	/**
	 * Lists datafile I/O statistics by joining {@code V$DATAFILE} with
	 * {@code V$FILESTAT} (physical/block reads and writes, average I/O time).
	 *
	 * @return one row per datafile, ordered by file id
	 */
	public List<Map<String, Object>> listDatafileIo() {
		return db.queryForList("""
				SELECT d.file# AS file_id, d.name AS file_name, d.status, d.enabled,
				       d.bytes / 1024 / 1024 AS size_mb,
				       fs.phyrds AS physical_reads, fs.phywrts AS physical_writes,
				       fs.phyblkrd AS block_reads, fs.phyblkwrt AS block_writes,
				       fs.readtim AS read_time_ms, fs.writetim AS write_time_ms,
				       fs.avgiotim AS avg_io_ms
				FROM v$datafile d
				LEFT JOIN v$filestat fs ON fs.file# = d.file#
				ORDER BY d.file#
				""", Map.of());
	}
}
