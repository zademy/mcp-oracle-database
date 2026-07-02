package com.zademy.mcp.oracle.db.service;

import org.springframework.dao.DataRetrievalFailureException;

/**
 * Test-only bean placed in the {@code service} package so the
 * {@code SentryCaptureAspect} pointcut
 * ({@code execution(* com.zademy.mcp.oracle.db.service..*(..))}) matches it.
 * Used by {@link com.zademy.mcp.oracle.db.observability.SentryAopWiringTest}
 * to verify the AOP wiring end-to-end without hitting a real database.
 */
public class ProbeService {

	public void boom() {
		throw new RuntimeException("probe: boom");
	}

	public void denied() {
		throw new DataRetrievalFailureException("probe: denied");
	}
}
