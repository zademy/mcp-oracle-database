package com.zademy.mcp.oracle.db.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decorates an Oracle error message with a short, actionable hint for the parse
 * errors an LLM most often triggers when authoring Oracle SQL. Pure and
 * stateless; used by the {@code run_query} / {@code execute_dml*} /
 * {@code explain_plan} tool catch blocks.
 *
 * <p>This is <strong>not</strong> a SQL gate and does not alter SQL text or
 * results &mdash; Oracle remains authoritative. It only appends a "likely cause
 * &rarr; fix" line to the error string the client already receives, so the LLM
 * can self-correct faster.
 */
public final class SqlErrorHints {

	private static final String PREFIX = "Oracle error: ";

	private static final String FOOTER =
			"\n  ↳ Pull the @oracle-sql-style prompt for the full authoring rules.";

	/** Lower-cased ORA code -> one-line hint. Order matters only for display. */
	private static final Map<String, String> HINTS = new LinkedHashMap<>();

	static {
		HINTS.put("ora-00923",
				"Likely cause: a reserved word used as an alias without AS, a missing comma between SELECT-list columns, or a non-quoted/mis-quoted identifier. Fix: alias with AS or \"alias\"; separate every column with a comma; quote mixed-case identifiers with double quotes.");
		HINTS.put("ora-00936",
				"Likely cause: missing operand — empty SELECT list, dangling/trailing comma, or a missing value after an operator. Fix: complete each expression and remove trailing commas.");
		HINTS.put("ora-00904",
				"Likely cause: a column/alias name is misspelled or absent from this table. Fix: call describe_table to confirm the exact name; remember Oracle folds unquoted identifiers to UPPERCASE.");
		HINTS.put("ora-00911",
				"Likely cause: a trailing semicolon ';' or a wrong quote character (backtick/bracket). Fix: send a single statement with no trailing ';' and use double quotes for identifiers, single quotes for literals.");
		HINTS.put("ora-00933",
				"Likely cause: extra text after the statement, or a non-Oracle clause (e.g. MySQL LIMIT). Fix: send one statement only; paginate with OFFSET n ROWS FETCH NEXT m ROWS ONLY, not LIMIT.");
		HINTS.put("ora-00907",
				"Likely cause: an unbalanced '('. Fix: count and match parentheses in function calls and subqueries.");
		HINTS.put("ora-01756",
				"Likely cause: an unterminated string literal. Fix: close every '...'; escape a literal quote as '' (two single quotes).");
		HINTS.put("ora-01790",
				"Likely cause: mismatched column types/counts in a UNION/UNION ALL/MINUS/INTERSECT. Fix: align the number and type of columns in each branch; use TO_DATE/TO_NUMBER for explicit conversion.");
		HINTS.put("ora-00942",
				"Likely cause: the table/view name is wrong, or the least-privilege user has no SELECT grant on it. Fix: call list_tables/describe_table to confirm the exact schema.name; visibility is bounded by the grants in db/setup_least_privilege_user.sql.");
		HINTS.put("ora-01031",
				"By design: the least-privilege user has no DDL/DCL privileges. Fix: structural changes (CREATE/ALTER/DROP/GRANT/...) must be run by a DBA; do not widen the MCP user's grants.");
	}

	private SqlErrorHints() {
	}

	/**
	 * Returns the hint for a known ORA code found in the message, or an empty
	 * string if no rule matches. Exposed for testing.
	 *
	 * @param message the raw error text (may be null)
	 * @return the matching hint, or {@code ""}
	 */
	public static String hintFor(String message) {
		if (message == null || message.isBlank()) {
			return "";
		}
		String lower = message.toLowerCase();
		for (Map.Entry<String, String> e : HINTS.entrySet()) {
			if (lower.contains(e.getKey())) {
				return e.getValue();
			}
		}
		return "";
	}

	/**
	 * Builds the full error string returned to the MCP client: the Oracle
	 * message, plus a hint line when a known code is recognised, plus a pointer
	 * to the full authoring rules. When no rule matches, the original message is
	 * returned unchanged (apart from the standard prefix).
	 *
	 * @param oracleMessage the raw message from the {@code DataAccessException}
	 * @return a client-facing error string, never {@code null}
	 */
	public static String enrich(String oracleMessage) {
		String msg = oracleMessage == null ? "" : oracleMessage;
		String hint = hintFor(msg);
		return hint.isBlank() ? PREFIX + msg : PREFIX + msg + "\n  ↳ " + hint + FOOTER;
	}
}
