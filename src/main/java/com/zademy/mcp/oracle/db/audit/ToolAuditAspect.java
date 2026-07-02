package com.zademy.mcp.oracle.db.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Populates the SLF4J {@link MDC} with the current MCP tool name and formatted
 * parameters for the duration of every {@code @McpTool} call.
 *
 * <p>The audit subsystem's {@link SqlAuditAspect} reads these MDC values from
 * the calling (virtual) thread when it observes a SQL execution inside
 * {@link com.zademy.mcp.oracle.db.persistence.OracleDataAccess}, so each
 * recorded SQL statement is correlated back to the tool and arguments that
 * produced it — without the data-access layer itself depending on the tool
 * layer.
 *
 * <p><b>Virtual-thread safety.</b> SLF4J's MDC is {@code ThreadLocal}-based.
 * Virtual threads preserve {@code ThreadLocal} values across parking, and the
 * blocking JDBC call chain (tool &#8594; service &#8594; data-access) executes
 * on the same thread, so MDC values propagate correctly. If any service ever
 * offloads work to a different thread (reactive / async), this correlation
 * would break for that path; the audit entry would fall back to
 * {@code "(unknown)"}.
 *
 * <p><b>Param formatting.</b> Parameters are formatted as
 * {@code name=value, name2=value2} using the reflective parameter names of the
 * tool method. This mirrors what the client sent and is adequate for an audit
 * trail. SQL literals and identifiers are not redacted here — the connected
 * Oracle user is least-privilege, and the SQL is itself logged verbatim by the
 * data-access aspect.
 */
@Aspect
@Component
public class ToolAuditAspect {

	/** MDC key carrying the {@code @McpTool(name=...)} value. */
	static final String MDC_TOOL = "mcp.tool";
	/** MDC key carrying the formatted {@code name=value, ...} parameter list. */
	static final String MDC_PARAMS = "mcp.params";

	/**
	 * Wraps every method in the tools package, pushes tool metadata into the
	 * MDC, proceeds the call, and clears the MDC in a {@code finally} block.
	 *
	 * <p>The pointcut is the whole {@code tools} package rather than a
	 * reflection check for {@code @McpTool} because every public method in
	 * that package is, by convention, an MCP tool entry point; matching the
	 * package keeps the pointcut cheap and obvious.
	 *
	 * @param pjp the AOP join point
	 * @return whatever the wrapped tool method returns
	 * @throws Throwable whatever the wrapped tool method throws
	 */
	@Around("execution(* com.zademy.mcp.oracle.db.tools..*(..))")
	public Object recordToolContext(ProceedingJoinPoint pjp) throws Throwable {
		MethodSignature sig = (MethodSignature) pjp.getSignature();
		Method method = sig.getMethod();
		MDC.put(MDC_TOOL, method.getName());
		MDC.put(MDC_PARAMS, formatParams(method, pjp.getArgs()));
		try {
			return pjp.proceed();
		} finally {
			MDC.remove(MDC_TOOL);
			MDC.remove(MDC_PARAMS);
		}
	}

	/**
	 * Formats the tool's parameter list as {@code name=value, name2=value2}.
	 * Defensive on every element so an exception or {@code null} argument
	 * never breaks the tool call itself.
	 *
	 * @param method the reflective tool method (parameter names come from here)
	 * @param args   the runtime arguments; may be shorter than the parameter
	 *               count for varargs
	 * @return a comma-separated {@code name=value} string, never {@code null}
	 */
	private static String formatParams(Method method, Object[] args) {
		Parameter[] params = method.getParameters();
		if (params.length == 0 || args == null || args.length == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int n = Math.min(params.length, args.length);
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(params[i].getName()).append('=');
			Object v = args[i];
			sb.append(v == null ? "null" : safeToString(v));
		}
		return sb.toString();
	}

	/**
	 * Isolates failures in a parameter's {@code toString()} so a misbehaving
	 * argument can never prevent the MCP tool from running.
	 */
	private static String safeToString(Object v) {
		try {
			return v.toString();
		} catch (Exception e) {
			return "<toString-threw " + e.getClass().getSimpleName() + ">";
		}
	}
}
