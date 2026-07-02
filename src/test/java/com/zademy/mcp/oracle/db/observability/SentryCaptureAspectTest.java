package com.zademy.mcp.oracle.db.observability;

import io.sentry.Sentry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.springframework.dao.DataAccessException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link SentryCaptureAspect}.
 *
 * <p>Verifies that all service-layer exceptions are forwarded to
 * {@link Sentry#captureException} — the aspect no longer filters any
 * exception type (Fase 2 removed the OperationNotAllowedException filter).
 *
 * <p>Uses {@link MockedStatic} so no real Sentry event is emitted during the run,
 * regardless of whether a DSN is configured.
 */
class SentryCaptureAspectTest {

	private final SentryCaptureAspect aspect = new SentryCaptureAspect();

	@Test
	@DisplayName("RuntimeException -> Sentry.captureException called once")
	void capturesRuntimeException() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			aspect.captureServiceExceptions(new RuntimeException("boom"));

			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(1));
		}
	}

	@Test
	@DisplayName("DataAccessException -> captured")
	void capturesDataAccessException() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			aspect.captureServiceExceptions(new TestDataAccessException("conn lost"));

			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(1));
		}
	}

	@Test
	@DisplayName("any Throwable subclass (including Error) is forwarded, never swallowed")
	void capturesErrorSubclass() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			aspect.captureServiceExceptions(new Error("fatal"));

			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(1));
		}
	}

	/** Minimal concrete subclass so {@code instanceof DataAccessException} matches in tests. */
	private static final class TestDataAccessException extends DataAccessException {
		TestDataAccessException(String msg) {
			super(msg);
		}
	}
}
