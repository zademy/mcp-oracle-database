package com.zademy.mcp.oracle.db.audit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap display-only classifier that inspects the leading token of a SQL
 * string to produce a coarse {@code kind}/{@code type} hint for the audit log.
 *
	 * <p>This is a display-only hint, not a security gate. The server enforces
	 * safety through the least-privilege Oracle user; statements that reach the
	 * audit aspect (at {@link com.zademy.mcp.oracle.db.persistence.OracleDataAccess})
	 * are either AI-supplied SQL or internally assembled metadata queries. The
	 * sniff here exists purely so the audit entry has a human-useful
	 * {@code READ}/{@code WRITE}/{@code OTHER} tag without re-parsing every
	 * metadata query.
 *
 * <p>The implementation is intentionally trivial: a single case-insensitive
 * regex over the first non-whitespace token. It does not need to be perfect —
 * it only needs to be right for the overwhelmingly common shapes launched by
 * this server.
 */
final class SqlKindSniffer {

	private static final Pattern LEADING = Pattern.compile("^\\s*(\\w+)");

	/** Canonical kind value for unrecognised statements. */
	static final String KIND_OTHER = "OTHER";
	/** Canonical type value for unrecognised statements. */
	static final String TYPE_OTHER = "OTHER";
	static final String TYPE_READ = "READ";
	static final String TYPE_WRITE = "WRITE";

	private SqlKindSniffer() {
	}

	/**
	 * Returns the coarse kind ({@code SELECT}, {@code INSERT}, ...).
	 *
	 * @param sql the SQL text to sniff; never {@code null}
	 * @return the uppercased first token, or {@link #KIND_OTHER} if blank/unmatched
	 */
	static String kind(String sql) {
		if (sql == null) {
			return KIND_OTHER;
		}
		Matcher m = LEADING.matcher(sql);
		return m.find() ? m.group(1).toUpperCase() : KIND_OTHER;
	}

	/**
	 * Maps the leading token to {@code READ}/{@code WRITE}/{@code OTHER}.
	 *
	 * @param sql the SQL text to sniff; never {@code null}
	 * @return one of {@link #TYPE_READ}, {@link #TYPE_WRITE}, {@link #TYPE_OTHER}
	 */
	static String type(String sql) {
		switch (kind(sql)) {
			case "SELECT", "WITH" -> {
				return TYPE_READ;
			}
			case "INSERT", "UPDATE", "DELETE", "MERGE", "COMMENT" -> {
				return TYPE_WRITE;
			}
			default -> {
				return TYPE_OTHER;
			}
		}
	}
}
