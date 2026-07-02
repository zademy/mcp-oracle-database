package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.PerformanceService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tools exposing read-only Oracle performance diagnostics from V$ views.
 * Requires SELECT_CATALOG_ROLE; never modifies instance state.
 */
@Component
public class PerformanceTools {

	private final PerformanceService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the performance service that queries V$ diagnostic views
	 */
	public PerformanceTools(PerformanceService service) {
		this.service = service;
	}

	/**
	 * Lists currently ACTIVE sessions with username, machine, program, last
	 * call elapsed, module, and logon time.
	 *
	 * @param username optional Oracle username filter; {@code null} returns all active sessions
	 * @param sid      optional session-id filter; {@code null} returns all
	 * @return rows describing each active session
	 */
	@McpTool(name = "list_active_sessions",
			description = "List currently ACTIVE sessions with username, machine, program, last call elapsed, module and logon time. Optional username and sid filters.")
	public List<Map<String, Object>> listActiveSessions(
			@McpToolParam(description = "Optional Oracle username filter.", required = false) String username,
			@McpToolParam(description = "Optional session id (SID) filter.", required = false) Integer sid) {
		return service.listActiveSessions(username, sid);
	}

	/**
	 * Lists sessions currently blocked by another session.
	 *
	 * @return rows with blocker SID/username/program and the wait event being waited on
	 */
	@McpTool(name = "list_blocked_sessions",
			description = "List sessions currently blocked by another session, with the blocker sid/username/program and the wait event being waited on.")
	public List<Map<String, Object>> listBlockedSessions() {
		return service.listBlockedSessions();
	}

	/**
	 * Lists DML/transaction/user locks held and requested.
	 *
	 * @return rows with locked object name and decoded lock mode
	 */
	@McpTool(name = "list_locks",
			description = "List DML/transaction/user locks held and requested, with the locked object name and lock mode (decoded).")
	public List<Map<String, Object>> listLocks() {
		return service.listLocks();
	}

	/**
	 * Returns the full SQL text currently being executed by a session.
	 *
	 * @param sid       session id (SID)
	 * @param serialNum session serial number
	 * @return the SQL text, or an empty string if the session is not executing SQL
	 */
	@McpTool(name = "get_session_sql_text",
			description = "Return the full SQL text currently being executed by a session, given its sid and serial#.")
	public String getSessionSqlText(
			@McpToolParam(description = "Session id (SID).", required = true) int sid,
			@McpToolParam(description = "Session serial number.", required = true) int serialNum) {
		return service.getSessionSqlText(sid, serialNum);
	}

	/**
	 * Returns top-N SQL statements ordered by the specified metric.
	 *
	 * @param orderBy sort key: {@code buffer_gets} (default), {@code disk_reads}, {@code elapsed_time}, {@code executions}, or {@code cpu_time}
	 * @param limit   number of rows to return; {@code null} defaults to 10
	 * @return rows with sql_id, executions, gets/reads/elapsed, and a 200-char SQL preview
	 */
	@McpTool(name = "list_top_sql",
			description = "Top-N SQL statements ordered by buffer_gets (default), disk_reads, elapsed_time, executions or cpu_time. Returns sql_id, executions, gets/reads/elapsed and a 200-char SQL preview.")
	public List<Map<String, Object>> listTopSql(
			@McpToolParam(description = "Sort key: 'buffer_gets' (default), 'disk_reads', 'elapsed_time', 'executions', 'cpu_time'.", required = false) String orderBy,
			@McpToolParam(description = "Number of rows to return (default 10).", required = false) Integer limit) {
		return service.listTopSql(orderBy, limit == null ? 10 : limit);
	}

	/**
	 * Lists current wait events from {@code V$SESSION_WAIT}.
	 *
	 * @param sid       optional session-id filter; {@code null} returns all sessions
	 * @param waitClass optional wait-class filter (e.g. {@code "Application"}, {@code "Concurrency"}, {@code "User I/O"})
	 * @return rows with event name, wait time, and session details
	 */
	@McpTool(name = "get_wait_events",
			description = "List current wait events from V$SESSION_WAIT. Optional sid filter and wait_class filter (e.g. 'Application','Concurrency','User I/O').")
	public List<Map<String, Object>> getWaitEvents(
			@McpToolParam(description = "Optional session id (SID) filter.", required = false) Integer sid,
			@McpToolParam(description = "Optional wait class filter (e.g. 'User I/O', 'Application').", required = false) String waitClass) {
		return service.getWaitEvents(sid, waitClass);
	}

	/**
	 * Lists long-running operations from {@code V$SESSION_LONGOPS}.
	 * <p>
	 * Includes backups, stats gathering, and long queries with progress
	 * percentage and ETA. Only in-progress operations are shown.
	 *
	 * @param sid optional session-id filter; {@code null} returns all
	 * @return rows with operation name, progress percent, and time remaining
	 */
	@McpTool(name = "get_session_longops",
			description = "List long-running operations from V$SESSION_LONGOPS (backups, stats gathering, long queries) with progress percentage and ETA. Optional sid filter; only in-progress ops shown.")
	public List<Map<String, Object>> getSessionLongops(
			@McpToolParam(description = "Optional session id (SID) filter.", required = false) Integer sid) {
		return service.getSessionLongops(sid);
	}

	/**
	 * Returns Oracle instance information.
	 *
	 * @return a map with name, host, version, startup time, status, archiver, and thread
	 */
	@McpTool(name = "get_instance_info",
			description = "Return Oracle instance information: name, host, version, startup time, status, archiver, thread.")
	public Map<String, Object> getInstanceInfo() {
		return service.getInstanceInfo();
	}

	/**
	 * Returns database-level information.
	 *
	 * @return a map with name, creation date, log mode, open mode, role, and flashback status
	 */
	@McpTool(name = "get_database_info",
			description = "Return database-level information: name, creation date, log mode (archivelog/noarchivelog), open mode, role, flashback status.")
	public Map<String, Object> getDatabaseInfo() {
		return service.getDatabaseInfo();
	}

	/**
	 * Returns cumulative system statistics from {@code V$SYSSTAT}.
	 *
	 * @return rows with parse count, execute count, DB time, physical reads/writes, etc.
	 */
	@McpTool(name = "get_system_stats",
			description = "Return cumulative system statistics from V$SYSSTAT (parse count, execute count, DB time, physical reads/writes, etc.).")
	public List<Map<String, Object>> getSystemStats() {
		return service.getSystemStats();
	}

	/**
	 * Returns the database time model from {@code V$SYS_TIME_MODEL}.
	 *
	 * @return rows with DB CPU, DB time, parse time elapsed, hard parse elapsed, etc.
	 */
	@McpTool(name = "get_db_time_model",
			description = "Return the database time model from V$SYS_TIME_MODEL: DB CPU, DB time, parse time elapsed, hard parse elapsed, etc.")
	public List<Map<String, Object>> getDbTimeModel() {
		return service.getDbTimeModel();
	}

	/**
	 * Returns Oracle initialization parameters from {@code V$PARAMETER}.
	 *
	 * @param name optional LIKE pattern for the parameter name (case-insensitive, supports {@code %}); {@code null} lists all
	 * @return rows with type, value, default/modified flags, and modifiability
	 */
	@McpTool(name = "get_parameter",
			description = "Return Oracle initialization parameters from V$PARAMETER with type, value, default/modified flags and modifiability. Optional name LIKE pattern (case-insensitive, supports %).")
	public List<Map<String, Object>> getParameter(
			@McpToolParam(description = "Optional LIKE pattern for the parameter name (case-insensitive, supports '%' wildcard). Omit to list all.", required = false) String name) {
		return service.getParameter(name);
	}

	/**
	 * Lists datafiles with their IO statistics.
	 *
	 * @return rows with size, physical reads/writes, block reads/writes, and average IO time per file
	 */
	@McpTool(name = "list_datafile_io",
			description = "List datafiles with size, physical reads/writes, block reads/writes and average IO time per file.")
	public List<Map<String, Object>> listDatafileIo() {
		return service.listDatafileIo();
	}
}
