package com.zademy.mcp.oracle.db.audit;

import java.time.Instant;

/**
 * Immutable record describing a single SQL execution observed by the audit
 * subsystem at the {@link com.zademy.mcp.oracle.db.persistence.OracleDataAccess}
 * chokepoint.
 *
 * <p>One {@code AuditEntry} is produced per SQL statement that reaches Oracle
 * (both AI-supplied SQL and internal metadata queries). It is the in-memory
 * representation serialised to the per-session {@code .txt} file by
 * {@link AuditLogWriter}.
 *
 * @param instant        moment the statement started executing (before the DB call)
 * @param tool           name of the MCP tool that triggered the call
 *                       ({@code @McpTool(name=...)}), or {@code "(unknown)"} when
 *                       no tool context was propagated via MDC (e.g. startup wiring)
 * @param params         formatted tool parameters ({@code name=value, ...}),
 *                       or {@code ""} when none
 * @param sql            the full SQL text launched against Oracle; never {@code null}
 * @param kind           coarse statement-kind hint derived from the leading token
 *                       ({@code SELECT}, {@code INSERT}, {@code UPDATE},
 *                       {@code DELETE}, {@code MERGE}, {@code COMMENT},
 *                       {@code EXPLAIN}, {@code BEGIN}, {@code DECLARE} or
	 *                       {@code OTHER}); display-only hint derived from the
	 *                       leading token (there is no application-layer
	 *                       classifier)
 * @param type           {@code READ}, {@code WRITE} or {@code OTHER} — display hint
 *                       matching {@link kind}
 * @param outcome        {@code OK}, {@code ERROR}; {@code BLOCKED} is intentionally
 *                       not produced here because blocked statements never reach
 *                       the data-access layer
 * @param rowsOrAffected for {@code OK}: number of rows returned by a SELECT, rows
 *                       affected by a DML statement, or {@code -1} when the call
 *                       returns no count (e.g. {@code execute}); for {@code ERROR}: {@code -1}
 * @param errorMessage   {@code null} on {@code OK}; otherwise
 *                       {@code "<ExceptionClass>: <message>"} from the thrown
 *                       {@link org.springframework.dao.DataAccessException}
 * @param durationMs     wall-clock duration of the DB call in milliseconds;
 *                       {@code >= 0}
 */
public record AuditEntry(
		Instant instant,
		String tool,
		String params,
		String sql,
		String kind,
		String type,
		String outcome,
		long rowsOrAffected,
		String errorMessage,
		long durationMs
) {
}
