package com.zademy.mcp.oracle.db.observability;

import io.sentry.Sentry;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Captures unhandled exceptions thrown from the service layer and reports
 * them to Sentry.
 *
 * <p>This is the single place that calls {@link Sentry#captureException},
 * so tool classes never need a manual {@code Sentry.captureException(...)}
 * in their catch blocks. The pointcut covers every bean in
 * {@code com.zademy.mcp.oracle.db.service..*}, which is where real
 * exceptions originate (database errors, parse failures, timeouts).
 *
 * <p>Oracle privilege denials (for example {@code ORA-01031} when the
 * least-privilege user attempts a disallowed statement) now surface here as
 * {@code DataAccessException}s and are reported, which is useful signal: it
 * indicates someone tried an operation the grants do not permit.
 */
@Aspect
@Component
public class SentryCaptureAspect {

	@AfterThrowing(
			pointcut = "execution(* com.zademy.mcp.oracle.db.service..*(..))",
			throwing = "ex"
	)
	public void captureServiceExceptions(Throwable ex) {
		Sentry.captureException(ex);
	}
}
