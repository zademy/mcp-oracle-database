package com.zademy.mcp.oracle.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full context load. Disabled unless the database environment variables are set,
 * because the application context wires a real Oracle {@code DataSource} and
 * Hikari validates the connection on startup. Run this against a live database:
 * <pre>
 *   ORACLE_DB_URL=... ORACLE_DB_USERNAME=... ORACLE_DB_PASSWORD=... ./mvnw test
 * </pre>
 * Security is enforced by the least-privilege Oracle user (no DDL/DCL privileges);
 * the identifier helpers are covered by {@code SqlIdentifiersTest}.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ORACLE_DB_URL", matches = ".+")
class McpOracleDbApplicationTests {

	@Test
	void contextLoads() {
	}
}
