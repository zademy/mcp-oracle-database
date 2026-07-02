package com.zademy.mcp.oracle.db;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-env-gated smoke test that boots the FULL application context without a real
 * Oracle database, so startup/wiring regressions are caught on every {@code ./mvnw test}.
 *
 * <p>The production Oracle {@code DataSource} is replaced by an in-memory H2 database
 * running in Oracle-compatibility mode: Hikari validates a connection at startup
 * ({@code initializationFailTimeout}) and the {@code SELECT 1 FROM DUAL} test query
 * resolves, so the pool initialises without Oracle.
 *
 * <p>The MCP server is intentionally <b>disabled</b> here ({@code spring.ai.mcp.server.enabled=false}).
 * The Spring AI server starts background threads that are not tied to the Spring lifecycle and
 * are not stopped by {@link org.springframework.context.ConfigurableApplicationContext#close()},
 * which keeps the forked Surefire JVM alive ~30s after the tests pass. Disabling it keeps the
 * unit tier fast and clean. The tool-host {@code @Component}s are still created by component
 * scanning, so the {@code @McpTool} inventory is still verified; the live MCP server
 * (initialize -> listTools -> callTool) is exercised end-to-end by {@code McpStdioEndToEndIT},
 * which spawns the packaged jar as a real STDIO subprocess.
 *
 * <p>This test exists to catch the class of bug where a config change (e.g. a Logback pattern
 * referencing an empty property) crashes {@code SpringApplication.run} before any tool runs.
 * That crash happens during bootstrap, before the MCP server bean, so disabling the server does
 * not weaken this regression net.
 */
@SpringBootTest(properties = {
		// H2 in Oracle-compatibility mode: the connection-test-query
		// "SELECT 1 FROM DUAL" resolves and the Hikari pool initialises without Oracle.
		"spring.datasource.url=jdbc:h2:mem:mcp-smoke;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.hikari.connection-test-query=SELECT 1 FROM DUAL",
		// Disable the MCP server: its background threads outlive the Spring context and
		// would keep the Surefire fork alive ~30s after the tests pass. Live MCP behaviour
		// is covered by the end-to-end STDIO harness (McpStdioEndToEndIT).
		"spring.ai.mcp.server.enabled=false",
		// Neutralise Sentry so no network call happens during the test.
		"sentry.dsn="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpOracleDbApplicationSmokeTest {

	/** Number of {@code @Component} host classes that carry at least one {@code @McpTool}. */
	private static final int EXPECTED_TOOL_HOSTS = 11;

	/** Total {@code @McpTool}-annotated methods across every host bean. */
	private static final int EXPECTED_TOOL_METHODS = 70;

	@Autowired
	ApplicationContext context;

	@Test
	void contextStartsWithoutOracle() {
		assertThat(context).as("full Spring context must boot without a real Oracle DB").isNotNull();
		assertThat(context.getBean(OracleDataAccess.class)).as("OracleDataAccess").isNotNull();
	}

	@Test
	void allMcpToolsAreRegistered() {
		ToolScan scan = scanMcpTools();
		assertThat(scan.hostBeans())
				.as("@McpTool host bean count (the *Tools classes)")
				.isEqualTo(EXPECTED_TOOL_HOSTS);
		assertThat(scan.toolMethods())
				.as("@McpTool method count across all host beans")
				.isEqualTo(EXPECTED_TOOL_METHODS);
	}

	/**
	 * Walks every bean definition and counts {@code @McpTool}-annotated methods.
	 *
	 * <p>{@link #realClass} unwraps any CGLIB/ByteBuddy proxy an aspect may have wrapped
	 * around a tool bean, so annotations are read from the real user class (annotations
	 * are not carried on overridden proxy methods).
	 */
	private ToolScan scanMcpTools() {
		int hosts = 0;
		int methods = 0;
		for (String name : context.getBeanDefinitionNames()) {
			Class<?> exposed = context.getType(name);
			if (exposed == null || !exposed.getName().startsWith("com.zademy.mcp.oracle.db.tools")) {
				continue;
			}
			Class<?> target = realClass(context.getBean(name));
			int count = (int) Arrays.stream(target.getDeclaredMethods())
					.filter(m -> m.isAnnotationPresent(McpTool.class))
					.count();
			if (count > 0) {
				hosts++;
				methods += count;
			}
		}
		return new ToolScan(hosts, methods);
	}

	/**
	 * Returns the user class behind a possible AOP proxy by walking up the class
	 * hierarchy past generated subclasses (CGLIB/ByteBuddy use a {@code $$} marker).
	 *
	 * @param bean the bean instance (may be proxied)
	 * @return the original user class
	 */
	private static Class<?> realClass(Object bean) {
		Class<?> c = bean.getClass();
		while (c != null && c.getName().contains("$$")) {
			c = c.getSuperclass();
		}
		return c != null ? c : bean.getClass();
	}

	private record ToolScan(int hostBeans, int toolMethods) { }
}
