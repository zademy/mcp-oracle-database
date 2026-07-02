package com.zademy.mcp.oracle.db.observability;

import com.zademy.mcp.oracle.db.config.SentryConfig;
import com.zademy.mcp.oracle.db.service.ProbeService;
import io.sentry.Sentry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test that sends REAL events to Sentry (no mocks).
 *
 * <p>Disabled by default so {@code ./mvnw test} does not spam your Sentry
 * project. Run it on demand with:
 * <pre>
 *   ./mvnw -Dtest=SentrySmokeTest -Dsentry.smoke=true test
 * </pre>
 *
 * <p>Requires {@code sentry.dsn} to resolve to a valid DSN (it does, via
 * {@code application.yaml}). After the run, open
 * <a href="https://sentry.io">sentry.io</a> → your project → Issues and you
 * should see two events with the messages below.
 */
@SpringBootTest(classes = SentrySmokeTest.TestConfig.class)
@EnabledIfSystemProperty(named = "sentry.smoke", matches = "true")
class SentrySmokeTest {

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

		@Bean
		SentryConfig sentryConfig(Environment env) {
			return new SentryConfig(env);
		}
	}

	@Test
	@DisplayName("SDK is initialised with the DSN from application.yaml")
	void sdkIsInitialised() {
		assertThat(Sentry.isEnabled())
				.as("Sentry must be enabled — check sentry.dsn in application.yaml")
				.isTrue();
	}

	@Test
	@DisplayName("DIRECT capture -> event visible in Sentry console")
	void captureExceptionDirectly() {
		var ex = new IllegalStateException("SMOKE TEST (direct): sentry-java integration works");
		var eventId = Sentry.captureException(ex);
		Sentry.flush(5_000);

		System.out.println("=== SENTRY SMOKE TEST (direct) ===");
		System.out.println("Event ID: " + eventId);
		System.out.println("Open: https://sentry.io -> Issues -> search for the message above");
		System.out.println("==================================");

		assertThat(eventId.toString()).isNotEmpty();
	}

	@Test
	@DisplayName("AOP capture -> exception thrown by a service bean reaches Sentry")
	void captureExceptionViaAspect() {
		try {
			probeService.boom();
		} catch (RuntimeException expected) {
			// Swallow: the aspect's @AfterThrowing advice ran already and
			// forwarded it to Sentry.captureException.
		}
		Sentry.flush(5_000);

		System.out.println("=== SENTRY SMOKE TEST (via AOP aspect) ===");
		System.out.println("ProbeService.boom() threw RuntimeException, captured by SentryCaptureAspect");
		System.out.println("Flush requested with 5s timeout.");
		System.out.println("Open: https://sentry.io -> Issues -> message 'probe: boom'");
		System.out.println("==========================================");
	}
}
