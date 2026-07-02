package com.zademy.mcp.oracle.db.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the named-parameter bind maps passed to {@link
 * com.zademy.mcp.oracle.db.persistence.OracleDataAccess}. Unlike {@link Map#of},
 * the returned maps tolerate {@code null} values, which the optional-filter
 * dictionary queries depend on: a {@code null} filter binds as SQL {@code NULL}
 * so predicates of the form {@code (:x IS NULL OR col = :x)} match every row.
 *
 * <p>Use {@code Map.of} for maps whose values are required (non-null); use this
 * helper only when at least one bind value is an optional filter that may be
 * {@code null}.
 */
final class BindParams {

	private BindParams() {
	}

	static Map<String, Object> of(String k1, Object v1) {
		Map<String, Object> m = new HashMap<>();
		m.put(k1, v1);
		return m;
	}

	static Map<String, Object> of(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> m = new HashMap<>();
		m.put(k1, v1);
		m.put(k2, v2);
		return m;
	}

	static Map<String, Object> of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
		Map<String, Object> m = new HashMap<>();
		m.put(k1, v1);
		m.put(k2, v2);
		m.put(k3, v3);
		return m;
	}
}
