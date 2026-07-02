package com.zademy.mcp.oracle.db.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.sentry.Sentry;
import jakarta.annotation.PostConstruct;

/**
 * Initialises the Sentry client from environment variables.
 *
 * <p>Uses the core {@code io.sentry:sentry} SDK instead of
 * {@code sentry-spring-boot-jakarta} to avoid Spring Boot 4 starter
 * incompatibilities. Init is a no-op when {@code SENTRY_DSN} is unset so
 * local development and tests run without error reporting.
 *
 * <p>Sentry output goes to its own background transport and stderr/file
 * logs only; it never touches stdout, which is reserved for the MCP
 * JSON-RPC STDIO channel.
 */
@Configuration
public class SentryConfig {

	private static final Logger log = LoggerFactory.getLogger(SentryConfig.class);

	private final Environment env;

	public SentryConfig(Environment env) {
		this.env = env;
	}

	@PostConstruct
	void initSentry() {
		String dsn = env.getProperty("sentry.dsn");
		if (dsn == null || dsn.isBlank()) {
			log.info("Sentry disabled: no DSN configured (set SENTRY_DSN to enable).");
			return;
		}

		Sentry.init(options -> {
			options.setDsn(dsn);
			options.setEnvironment(env.getProperty("sentry.environment", "local"));
			options.setRelease(env.getProperty("sentry.release"));
		});

		log.info("Sentry initialised for environment '{}'.", env.getProperty("sentry.environment", "local"));
	}
}
