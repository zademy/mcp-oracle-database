package com.zademy.mcp.oracle.db.config;

/**
 * Configuration for the per-session SQL audit log.
 *
 * <p>Bound from the {@code oracle.mcp.audit.*} properties in
 * {@code application.yaml}. When {@link #enabled()} is {@code false} (the
 * default), the audit subsystem is fully inert: no files are created and the
 * aspects short-circuit without building entries.
 *
 * <p>The audit log is a plain-text {@code .txt} file written to
 * {@link #dir()}; one file per MCP session (JVM lifetime), appended on every
 * SQL execution that reaches Oracle.
 *
 * @param enabled whether the audit log is active; defaults to {@code false}
 *                (opt-in). Configured under {@code oracle.mcp.audit.enabled}
 *                / overridable with the {@code MCP_AUDIT_ENABLED} env var.
 * @param dir     directory where the per-session {@code .txt} files are
 *                written. Created on first write if it does not exist.
 *                Configured under {@code oracle.mcp.audit.dir} / overridable
 *                with the {@code MCP_AUDIT_DIR} env var.
 */
public record AuditProperties(
		boolean enabled,
		String dir
) {
}
