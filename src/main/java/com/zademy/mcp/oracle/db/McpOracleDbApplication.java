package com.zademy.mcp.oracle.db;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Application entry point for the Oracle Database MCP server.
 *
 * <p>Boots a Spring Boot context that exposes MCP tools (schema introspection,
 * DML execution, performance diagnostics) over the STDIO JSON-RPC transport
 * provided by Spring AI's MCP server starter.
 *
 * <p>Key wiring performed at startup:
 * <ul>
 *   <li>{@link OracleMcpProperties} are bound from {@code oracle.mcp.*} and
 *       applied to the {@link org.springframework.jdbc.core.JdbcTemplate}
 *       (timeout + max-rows caps).</li>
 *   <li>The Oracle {@link javax.sql.DataSource} is configured by Spring Boot
 *       auto-configuration from {@code ORACLE_DB_URL/USERNAME/PASSWORD}
 *       environment variables.</li>
 *   <li>{@link com.zademy.mcp.oracle.db.config.SentryConfig} initialises the
 *       Sentry SDK when {@code sentry.dsn} resolves to a non-empty value.</li>
 * </ul>
 *
 * <p><b>STDIO transport note:</b> this process speaks JSON-RPC over stdin/stdout.
 * Do not write to stdout from application code; logs go to stderr/file only.
 */
@SpringBootApplication
@EnableConfigurationProperties(OracleMcpProperties.class)
public class McpOracleDbApplication {

	/**
	 * Starts the Spring Boot application context and blocks until the MCP server
	 * is shut down (typically when the MCP client closes the STDIO pipe).
	 *
	 * @param args optional command-line arguments forwarded to Spring Boot
	 *             (rarely needed; configuration comes from
	 *             {@code application.yaml} and environment variables)
	 */
	public static void main(String[] args) {
		SpringApplication.run(McpOracleDbApplication.class, args);
	}

}
