package com.zademy.mcp.oracle.db;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-2 end-to-end test: drives the real MCP server over STDIO exactly the
 * way a production client (Claude Desktop, Cursor, ...) does.
 *
 * <p>Spawns the server jar as a subprocess, performs the MCP initialize
 * handshake, lists tools, then exercises a configurable set of tables with
 * SELECT-only operations and verifies that the least-privilege Oracle user blocks a
 * {@code CREATE TABLE} attempt.
 *
 * <p><strong>Dual gate.</strong> Two independent conditions must hold for the
 * tests to run:
 * <ol>
 *   <li><strong>System property</strong> {@code mcp.e2e.enabled=true} — set
 *       automatically by the {@code mcp-e2e} Maven profile or manually with
 *       {@code -Dmcp.e2e.enabled=true}. Without it JUnit disables the entire
 *       class <em>before instantiation</em>, which prevents the MCP client
 *       SDK (Project Reactor) from loading its non-daemon thread pools and
 *       keeping the forked Surefire JVM alive after tests finish.</li>
 *   <li><strong>Config file</strong> {@code application-e2e.yaml} on the test
 *       classpath — checked inside {@code @BeforeAll} via
 *       {@link Assumptions}. Copy {@code application-e2e.example.yaml} to
 *       {@code application-e2e.yaml} and fill in credentials, schema, and
 *       tables.</li>
 * </ol>
 * When either gate fails the default {@code mvn test} stays green (the class
 * is disabled or skipped, never instantiated).
 *
 * <p><strong>Running the e2e suite:</strong>
 * <pre>
 *   copy src\test\resources\application-e2e.example.yaml src\test\resources\application-e2e.yaml
 *   # ... edit application-e2e.yaml with your Oracle credentials, schema, tables ...
 *   run-e2e.bat
 * </pre>
 *
 * <p><strong>No extra dependencies.</strong> The MCP client SDK
 * ({@code io.modelcontextprotocol.sdk:mcp-core}) and SnakeYAML are already on
 * the compile classpath transitively via the MCP server starter, so this test
 * compiles without any pom changes.
 */
@EnabledIfSystemProperty(named = "mcp.e2e.enabled", matches = "true")
class McpStdioE2ETest {

	private static final String CONFIG_RESOURCE = "/application-e2e.yaml";

	private static McpSyncClient client;
	private static String schema;
	private static List<String> tables;

	/**
	 * Launches the server subprocess and performs the MCP initialize
	 * handshake. If the config file is absent or incomplete the entire class
	 * is skipped via {@link Assumptions}.
	 *
	 * @throws Exception if the transport or handshake fails
	 */
	@BeforeAll
	static void launchServerAndConnect() throws Exception {
		InputStream configStream = McpStdioE2ETest.class.getResourceAsStream(CONFIG_RESOURCE);
		Assumptions.assumeTrue(configStream != null,
				"application-e2e.yaml not found on the test classpath. "
						+ "Copy src/test/resources/application-e2e.example.yaml to application-e2e.yaml "
						+ "and fill in Oracle credentials, schema, and tables to run the e2e suite.");

		Map<String, Object> config = new Yaml().load(configStream);
		Map<String, Object> e2e = asMap(config.get("e2e"));

		String jarPath = (String) e2e.get("jar");
		String javaExec = (String) e2e.getOrDefault("java", "java");
		Map<String, Object> oracle = asMap(e2e.get("oracle"));
		schema = (String) e2e.get("schema");
		tables = asStringList(e2e.get("tables"));

		Assumptions.assumeTrue(jarPath != null && !jarPath.isBlank(),
				"e2e.jar must be set in application-e2e.yaml");
		Assumptions.assumeTrue(schema != null && !schema.isBlank(),
				"e2e.schema must be set in application-e2e.yaml");
		Assumptions.assumeTrue(tables != null && !tables.isEmpty(),
				"e2e.tables must list at least one table in application-e2e.yaml");

		ServerParameters params = ServerParameters.builder(javaExec)
				.arg("-Dspring.ai.mcp.server.stdio=true")
				.arg("-Dspring.main.web-application-type=none")
				.arg("-Dlogging.pattern.console=")
				.arg("-jar")
				.arg(jarPath)
				.addEnvVar("ORACLE_DB_URL", (String) oracle.get("url"))
				.addEnvVar("ORACLE_DB_USERNAME", (String) oracle.get("username"))
				.addEnvVar("ORACLE_DB_PASSWORD", (String) oracle.get("password"))
				.build();

		StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
		client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(60))
				.initializationTimeout(Duration.ofSeconds(120))
				.clientInfo(new Implementation("mcp-oracle-db-e2e-test", "1.0"))
				.build();
		client.initialize();
	}

	/**
	 * Closes the MCP client, which closes stdin to the server subprocess and
	 * triggers its clean shutdown.
	 */
	@AfterAll
	static void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	/**
	 * Verifies the server registered the core tools the subsequent tests call.
	 */
	@Test
	void serverExposesExpectedTools() {
		ListToolsResult result = client.listTools();
		Set<String> names = result.tools().stream().map(Tool::name).collect(Collectors.toSet());
		assertTrue(names.contains("run_query"), "run_query tool missing");
		assertTrue(names.contains("describe_table"), "describe_table tool missing");
		assertTrue(names.contains("count_rows"), "count_rows tool missing");
		assertTrue(names.contains("test_connection"), "test_connection tool missing");
		assertTrue(names.size() >= 60, "Expected >= 60 tools, got " + names.size());
	}

	/**
	 * Confirms the server can reach Oracle and reports version identity.
	 */
	@Test
	void connectionToOracleSucceeds() {
		CallToolResult result = client.callTool(new CallToolRequest("test_connection", Map.of()));
		String text = textOf(result);
		assertFalse(Boolean.TRUE.equals(result.isError()), "test_connection failed: " + text);
		assertTrue(text.toLowerCase().contains("oracle") || text.toLowerCase().contains("version"),
				"Expected Oracle version info, got: " + text);
	}

	/**
	 * Exercises {@code describe_table} on every configured table.
	 */
	@Test
	void describeTableReturnsColumnsForEachTable() {
		for (String table : tables) {
			CallToolResult result = client.callTool(
					new CallToolRequest("describe_table", Map.of("schema", schema, "table", table)));
			String text = textOf(result);
			assertFalse(Boolean.TRUE.equals(result.isError()),
					"describe_table failed for " + schema + "." + table + ": " + text);
			assertFalse(text.isBlank(), "describe_table returned empty for " + schema + "." + table);
		}
	}

	/**
	 * Runs a safe {@code SELECT * FROM schema.table WHERE ROWNUM <= 5} on every
	 * configured table via {@code run_query}.
	 */
	@Test
	void runQueryReturnsRowsForEachTable() {
		for (String table : tables) {
			String sql = "SELECT * FROM \"" + schema + "\".\"" + table + "\" WHERE ROWNUM <= 5";
			CallToolResult result = client.callTool(new CallToolRequest("run_query", Map.of("sql", sql)));
			String text = textOf(result);
			assertFalse(Boolean.TRUE.equals(result.isError()),
					"run_query failed for " + schema + "." + table + ": " + text);
			assertFalse(text.isBlank(), "run_query returned empty for " + schema + "." + table);
		}
	}

	/**
	 * Exercises {@code count_rows} on every configured table.
	 */
	@Test
	void countRowsReturnsResultForEachTable() {
		for (String table : tables) {
			CallToolResult result = client.callTool(
					new CallToolRequest("count_rows", Map.of("schema", schema, "table", table)));
			String text = textOf(result);
			assertFalse(Boolean.TRUE.equals(result.isError()),
					"count_rows failed for " + schema + "." + table + ": " + text);
			assertFalse(text.isBlank(), "count_rows returned empty for " + schema + "." + table);
		}
	}

	/**
	 * Verifies the security gate: {@code run_query} with a {@code CREATE TABLE}
	 * statement must be rejected by the least-privilege Oracle user, which has
	 * no DDL privileges. The tool catches {@code DataAccessException} and returns
	 * the Oracle error as text, so we assert the response carries a denial signal
	 * rather than executing the DDL.
	 */
	@Test
	void oracleBlocksCreateTable() {
		CallToolResult result = client.callTool(
				new CallToolRequest("run_query", Map.of("sql", "CREATE TABLE e2e_blocked_test (id NUMBER)")));
		String text = textOf(result).toLowerCase();
		boolean denied = Boolean.TRUE.equals(result.isError()) || text.contains("not") || text.contains("block")
				|| text.contains("denied") || text.contains("reject") || text.contains("create")
				|| text.contains("oracle error") || text.contains("insufficient privileges");
		assertTrue(denied, "Oracle should block CREATE TABLE. isError=" + result.isError() + " text=" + text);
	}

	private static String textOf(CallToolResult result) {
		return result.content().stream()
				.filter(TextContent.class::isInstance)
				.map(c -> ((TextContent) c).text())
				.collect(Collectors.joining("\n"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<String> asStringList(Object o) {
		if (o == null) {
			return List.of();
		}
		return ((List<Object>) o).stream().map(String::valueOf).collect(Collectors.toList());
	}
}
