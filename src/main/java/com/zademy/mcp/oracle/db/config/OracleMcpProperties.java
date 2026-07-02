package com.zademy.mcp.oracle.db.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-specific tuning for the MCP Oracle server.
 *
 * <p>Bound from the {@code oracle.mcp.*} properties in {@code application.yaml}.
 * These values are applied to the {@link JdbcTemplate} at startup and to the
 * data-access layer at runtime.
 *
 * <p>Each component is documented below to make the intent of every knob
 * explicit for operators and future maintainers.
 *
 * @param maxRows             hard cap on the number of rows any single
 *                            {@code SELECT}/{@code queryForList} call may
 *                            return. Bounds memory and response size when an
 *                            AI-generated query accidentally targets a very
 *                            large table. Configured under
 *                            {@code oracle.mcp.max-rows}.
 * @param queryTimeoutSeconds wall-clock budget in seconds for any single
 *                            statement. Statements that exceed it are aborted
 *                            by the JDBC driver, protecting the Hikari pool
 *                            from being starved by runaway queries. Configured
 *                            under {@code oracle.mcp.query-timeout-seconds}.
 * @param defaultSampleRows   number of rows returned by the {@code get_sample_data}
 *                            tool when the caller does not specify an explicit
 *                            count. Configured under
 *                            {@code oracle.mcp.default-sample-rows}.
 * @param audit               per-session SQL audit-log settings. Bound from
 *                            {@code oracle.mcp.audit.*}. Inert by default; see
 *                            {@link AuditProperties}.
 */
@ConfigurationProperties("oracle.mcp")
public record OracleMcpProperties(
		int maxRows,
		int queryTimeoutSeconds,
		int defaultSampleRows,
		AuditProperties audit
) {
}
