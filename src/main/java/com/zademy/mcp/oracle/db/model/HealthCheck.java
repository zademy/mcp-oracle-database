package com.zademy.mcp.oracle.db.model;

/**
 * Outcome of a single readiness probe within the health report.
 *
 * @param name   short identifier of the probe (e.g. {@code "database-connectivity"})
 * @param status {@code "PASS"}, {@code "WARN"}, or {@code "FAIL"}
 * @param detail human-readable explanation or measured value
 */
public record HealthCheck(
		String name,
		String status,
		String detail) {
}
