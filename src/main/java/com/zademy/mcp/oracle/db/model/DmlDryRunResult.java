package com.zademy.mcp.oracle.db.model;

/**
 * Result of {@code execute_dml_rollback_first}: the statement is executed inside
 * a transaction that is immediately rolled back, so nothing is persisted. This
 * proves the statement is valid and reveals the number of affected rows without
 * committing. The caller then invokes {@code execute_dml} to apply the change.
 *
 * @param detectedKind the parsed statement kind (e.g. {@code "INSERT"}, {@code "MERGE"})
 * @param rowsAffected rows reported by Oracle during the rolled-back execution
 * @param rolledBack   always {@code true} for this tool; confirms nothing was persisted
 * @param note         human-readable guidance for the AI client
 */
public record DmlDryRunResult(
		String detectedKind,
		int rowsAffected,
		boolean rolledBack,
		String note) {
}
