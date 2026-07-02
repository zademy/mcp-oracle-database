package com.zademy.mcp.oracle.db.observability;

import com.zademy.mcp.oracle.db.service.ProbeService;
import io.sentry.Sentry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Integration test verifying the full Spring AOP wiring:
 * <ul>
 *   <li>{@link SentryCaptureAspect} is registered and proxied by Spring</li>
 *   <li>its pointcut matches beans in {@code com.zademy.mcp.oracle.db.service..*}</li>
 *   <li>all exceptions thrown from a service bean reach {@link Sentry#captureException}</li>
 * </ul>
 *
 * <p>Uses a minimal {@link TestConfiguration} with just the aspect and a probe
 * service, so the heavy MCP STDIO context is not started. {@link Sentry} is
 * stubbed via {@link MockedStatic} so no real event is sent.
 */
@SpringBootTest(classes = SentryAopWiringTest.TestConfig.class)
class SentryAopWiringTest {

	@Autowired
	private ProbeService probeService;

	@TestConfiguration
	@EnableAspectJAutoProxy
	static class TestConfig {

		@Bean
		ProbeService probeService() {
			return new ProbeService();
		}

		@Bean
		SentryCaptureAspect sentryCaptureAspect() {
			return new SentryCaptureAspect();
		}
	}

	@Test
	@DisplayName("RuntimeException from service bean -> captured by Sentry via AOP")
	void aspectInterceptsServiceExceptionAndForwardsToSentry() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			assertThatThrownBy(probeService::boom)
					.isInstanceOf(RuntimeException.class)
					.hasMessage("probe: boom");

			sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
		}
	}

	@Test
	@DisplayName("DataAccessException from service bean -> forwarded to Sentry via AOP")
	void aspectForwardsDataAccessExceptionToSentry() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			assertThatThrownBy(probeService::denied)
					.isInstanceOf(DataAccessException.class);

			sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
		}
	}
}
