package com.zademy.mcp.oracle.db.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Observes every SQL-executing method of
 * {@link com.zademy.mcp.oracle.db.persistence.OracleDataAccess} and records an
 * {@link AuditEntry} to {@link AuditLogWriter}.
 *
 * <p>This is the single chokepoint for "all SQL hitting Oracle": both the
 * raw-SQL path used by AI-supplied statements ({@code run_query},
 * {@code execute_dml}, {@code explain_plan}, {@code suggest_index}) and the
	 * named-parameter path used by internal metadata queries ({@code list_tables},
	 * {@code get_ddl}, {@code ALL_*}, {@code DBMS_METADATA}, ...) and the callable
	 * path used by {@code call_procedure} all flow through it.
 *
 * <p>The aspect relies on {@link ToolAuditAspect} having populated
 * {@code mcp.tool} / {@code mcp.params} on the MDC for the same (virtual)
 * thread; when those keys are absent — for example during startup wiring —
 * the entry falls back to {@code "(unknown)"} and empty params.
 *
	 * <p>Statements the least-privilege Oracle user is not allowed to run are
	 * rejected by Oracle itself; those attempts still reach
	 * {@code OracleDataAccess} and are recorded here with an {@code ERROR}
	 * outcome (for example an {@code ORA-01031} privilege denial).
 */
@Aspect
@Component
public class SqlAuditAspect {

	private final AuditLogWriter writer;

	/**
	 * Spring-injected constructor.
	 *
	 * @param writer the destination for recorded entries
	 */
	public SqlAuditAspect(AuditLogWriter writer) {
		this.writer = writer;
	}

	/**
	 * Wraps the seven SQL-executing methods of {@code OracleDataAccess}.
	 *
	 * <p>The pointcut enumerates the exact method names rather than matching
	 * every public method so that {@code maxRows()} (a plain getter) is
	 * excluded.
	 *
	 * @param pjp the AOP join point
	 * @return whatever the wrapped method returns
	 * @throws Throwable whatever the wrapped method throws
	 */
	@Around("execution(* com.zademy.mcp.oracle.db.persistence.OracleDataAccess.queryForList(..)) "
			+ "|| execution(* com.zademy.mcp.oracle.db.persistence.OracleDataAccess.update(..)) "
			+ "|| execution(* com.zademy.mcp.oracle.db.persistence.OracleDataAccess.execute(..)) "
			+ "|| execution(* com.zademy.mcp.oracle.db.persistence.OracleDataAccess.queryForObject(..)) "
			+ "|| execution(* com.zademy.mcp.oracle.db.persistence.OracleDataAccess.call(..))")
	public Object auditSql(ProceedingJoinPoint pjp) throws Throwable {
		Instant start = Instant.now();
		String sql = extractSql(pjp.getArgs());
		String kind = SqlKindSniffer.kind(sql);
		String type = SqlKindSniffer.type(sql);
		String tool = safe(MDC.get(ToolAuditAspect.MDC_TOOL), "(unknown)");
		String params = safe(MDC.get(ToolAuditAspect.MDC_PARAMS), "");

		try {
			Object result = pjp.proceed();
			writer.append(build(start, sql, kind, type, tool, params, "OK", result, null));
			return result;
		} catch (Throwable ex) {
			String msg = describe(ex);
			writer.append(build(start, sql, kind, type, tool, params, "ERROR", null, msg));
			throw ex;
		}
	}

	/**
	 * Extracts the SQL text, which is always the first {@code String} argument
	 * of every wrapped method.
	 */
	private static String extractSql(Object[] args) {
		if (args == null || args.length == 0) {
			return "(no sql)";
		}
		Object first = args[0];
		return first instanceof String s ? s : "(no sql)";
	}

	/**
	 * Maps the result object to a row-count / affected-rows figure for display.
	 * {@code -1} means "no count available" (e.g. {@code execute()} returns void).
	 */
	private static long countOf(Object result) {
		if (result instanceof Integer i) {
			return i;
		}
		if (result instanceof Long l) {
			return l;
		}
		if (result instanceof List<?> list) {
			return list.size();
		}
		return -1L;
	}

	/**
	 * Builds the immutable {@link AuditEntry}, computing duration and choosing
	 * the {@code rowsOrAffected}/{@code errorMessage} fields from the outcome.
	 */
	private static AuditEntry build(Instant start, String sql, String kind, String type,
			String tool, String params, String outcome, Object result, String errorMessage) {
		long durationMs = Math.max(0L, Duration.between(start, Instant.now()).toMillis());
		return new AuditEntry(
				start,
				tool,
				params,
				sql,
				kind,
				type,
				outcome,
				outcome.equals("OK") ? countOf(result) : -1L,
				errorMessage,
				durationMs
		);
	}

	/** Null-safe accessor that substitutes a default when the value is blank. */
	private static String safe(String value, String fallback) {
		return (value == null || value.isBlank()) ? fallback : value;
	}

	/**
	 * Renders an exception as {@code "<Class>: <message>"}. Only
	 * {@link DataAccessException} is expected in normal operation, but the
	 * aspect treats any {@link Throwable} uniformly so it never loses an entry.
	 */
	private static String describe(Throwable ex) {
		String message = ex.getMessage();
		return ex.getClass().getName() + ": " + (message == null ? "(no message)" : message);
	}
}
