package com.zademy.mcp.oracle.db.security;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single source of truth for assembling Oracle identifiers and string literals
 * safely. Part of the defence-in-depth security model: user-supplied names are
 * validated against a strict allow-list before they are ever concatenated into
 * SQL, because identifiers cannot be bound as JDBC parameters.
 *
 * <p>Two validation tiers:
 * <ul>
 *   <li>{@link #validate} / {@link #quote} / {@link #qualified} / {@link #quoteCsv}
 *       &mdash; for <b>user-supplied</b> identifiers. Rejects anything outside
 *       {@code ^[A-Za-z][A-Za-z0-9_$#]{0,127}$}.</li>
 *   <li>{@link #quoteTrusted} / {@link #qualifiedTrusted} &mdash; for names read
 *       directly from Oracle's own dictionary ({@code ALL_*} views), which are by
 *       definition real identifiers (including quoted names with special chars).
 *       These skip the allow-list to avoid throwing on legitimate quoted names.</li>
 * </ul>
 */
public final class SqlIdentifiers {

	private static final Pattern VALID = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");

	private SqlIdentifiers() {
	}

	/**
	 * Validate a user-supplied Oracle identifier. Returns the name unchanged if it
	 * matches {@code ^[A-Za-z][A-Za-z0-9_$#]{0,127}$}, otherwise throws.
	 */
	public static String validate(String raw) {
		if (raw == null || !VALID.matcher(raw).matches()) {
			throw new IllegalArgumentException("Invalid Oracle identifier: " + raw);
		}
		return raw;
	}

	/** Validate and wrap a user-supplied identifier in double quotes: {@code EMP} -&gt; {@code "EMP"}. */
	public static String quote(String raw) {
		return "\"" + validate(raw) + "\"";
	}

	/** Validate and render a qualified two-part name: {@code (S,T)} -&gt; {@code "S"."T"}. */
	public static String qualified(String schema, String name) {
		return quote(schema) + "." + quote(name);
	}

	/** Validate a list of identifiers and join them as a CSV of quoted names: {@code [A,B]} -&gt; {@code "A", "B"}. */
	public static String quoteCsv(List<String> raw) {
		return raw.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
	}

	/**
	 * Wrap a DB-sourced identifier in double quotes <b>without</b> validation. Use
	 * only for names read from Oracle's own dictionary, which are guaranteed to be
	 * real identifiers and may include quoted names with special characters.
	 */
	public static String quoteTrusted(String name) {
		return "\"" + name + "\"";
	}

	/** Render a qualified two-part name from DB-sourced parts without validation. */
	public static String qualifiedTrusted(String schema, String name) {
		return quoteTrusted(schema) + "." + quoteTrusted(name);
	}

	/**
	 * Render a Java string as an Oracle SQL string literal, doubling any single
	 * quote to avoid breaking out of the literal.
	 */
	public static String stringLiteral(String value) {
		if (value == null) {
			return "NULL";
		}
		return "'" + value.replace("'", "''") + "'";
	}
}
