package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.model.ConnectionInfo;
import com.zademy.mcp.oracle.db.model.HealthReport;
import com.zademy.mcp.oracle.db.service.SystemService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP tool that reports database connectivity and identity, useful to verify
 * the server can reach Oracle and to confirm which least-privilege user it
 * connects as.
 */
@Component
public class SystemTools {

	private final SystemService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the system service that queries {@code v$version} and session context
	 */
	public SystemTools(SystemService service) {
		this.service = service;
	}

	/**
	 * Verifies connectivity to Oracle and reports the database version, name,
	 * current user, current schema, and instance name.
	 *
	 * @return a {@link ConnectionInfo} record with version and identity details
	 */
	@McpTool(name = "test_connection",
			description = "Verify connectivity to Oracle and report the database version, name, current user, current schema and instance name. WHEN TO CALL: only when the user explicitly asks to verify the connection or diagnose reachability; do NOT call as a preamble before answering a query about tables, data, or schema objects.")
	public ConnectionInfo testConnection() {
		return service.connectionInfo();
	}

	/**
	 * Runs a one-shot readiness battery: connectivity, version, catalog-role,
	 * invalid-objects and server configuration. Returns an aggregated report
	 * that an AI client can use to self-diagnose before running heavier tasks.
	 *
	 * @return a {@link HealthReport} with overall status and per-probe details
	 */
	@McpTool(name = "oracle_mcp_health_report",
			description = "One-shot readiness check: connectivity, database version, catalog-role, invalid objects, and server configuration (max-rows, query-timeout, audit). Returns an aggregated health report for quick self-diagnosis. WHEN TO CALL: only when the user explicitly asks for a health/readiness check; do NOT call as a preamble before other work.")
	public HealthReport healthReport() {
		return service.healthReport();
	}
}
