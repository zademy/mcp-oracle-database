package com.zademy.mcp.oracle.db.model;

import java.util.List;

/**
 * Aggregated one-shot readiness report produced by the
 * {@code oracle_mcp_health_report} tool.
 *
 * @param overallStatus {@code "UP"} (all probes pass),
 *                      {@code "DEGRADED"} (at least one WARN, no FAIL), or
 *                      {@code "DOWN"} (at least one FAIL)
 * @param databaseName  name of the database, or {@code null} if unreachable
 * @param checks        ordered list of individual probe results
 */
public record HealthReport(
		String overallStatus,
		String databaseName,
		List<HealthCheck> checks) {
}
