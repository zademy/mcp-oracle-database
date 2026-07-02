package com.zademy.mcp.oracle.db.model;

import java.util.List;

/**
 * Result of the {@code suggest_index} analysis. Carries the detected access
 * problem and a recommended {@code CREATE INDEX} statement (as text &mdash; the
 * tool never executes it; the operator applies it manually after DBA review).
 *
 * @param hasCandidates      whether at least one full-scan candidate was found
 * @param schema             owner of the target table (null if no candidate)
 * @param table              target table name (null if no candidate)
 * @param accessProblem      e.g. "TABLE ACCESS FULL"
 * @param predicateColumns   all predicate columns found for the table
 * @param recommendedColumns columns chosen for the index, ordered (empty if none)
 * @param recommendedDdl     ready-to-run CREATE INDEX DDL, or null
 * @param reasoning          human-readable explanation of the recommendation
 */
public record IndexSuggestion(
		boolean hasCandidates,
		String schema,
		String table,
		String accessProblem,
		List<String> predicateColumns,
		List<String> recommendedColumns,
		String recommendedDdl,
		String reasoning) {

	/** Build a "no candidate found" result. */
	public static IndexSuggestion empty(String reason) {
		return new IndexSuggestion(false, null, null, null, List.of(), List.of(), null, reason);
	}
}
