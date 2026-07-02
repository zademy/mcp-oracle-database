package com.zademy.mcp.oracle.db.model;

/**
 * One argument of a PL/SQL call. Bound from the JSON {@code args} array that the
 * client passes to the {@code call_procedure} tool.
 *
 * @param name      argument name (informational; used to key OUT values)
 * @param direction {@code IN}, {@code OUT} or {@code INOUT} (case-insensitive)
 * @param dataType  Oracle data type, e.g. {@code NUMBER}, {@code VARCHAR2},
 *                  {@code DATE}, {@code CLOB}, {@code REF CURSOR}
 * @param value     IN value (ignored for {@code OUT}-only arguments); may be
 *                  {@code null}
 */
public record PlsqlCallArg(
		String name,
		String direction,
		String dataType,
		Object value
) {
}
