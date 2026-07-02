package com.zademy.mcp.oracle.db.model;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code execute_dml_preview}: a non-executing dry-run that shows the
 * rows an UPDATE or DELETE <em>would</em> touch, derived from the statement's
 * WHERE clause. The DML itself is never executed.
 *
 * @param detectedKind      the parsed statement kind ({@code "UPDATE"} / {@code "DELETE"})
 * @param table             the target table reference as written in the statement
 * @param affectedRowCount  total number of rows matching the WHERE clause
 * @param sampleColumns     ordered column labels of the sample
 * @param sampleRows        a small sample of rows that would be affected
 * @param sampleCount       number of rows in {@code sampleRows}
 * @param note              human-readable guidance for the AI client
 */
public record DmlPreviewResult(
		String detectedKind,
		String table,
		int affectedRowCount,
		List<String> sampleColumns,
		List<Map<String, Object>> sampleRows,
		int sampleCount,
		String note) {
}
