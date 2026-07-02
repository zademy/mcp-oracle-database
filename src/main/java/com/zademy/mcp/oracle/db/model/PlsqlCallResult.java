package com.zademy.mcp.oracle.db.model;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a PL/SQL procedure or function via {@code call_procedure}.
 *
 * @param outParams    values of {@code OUT}/{@code INOUT} arguments, keyed by
 *                     argument name; empty for a procedure with no OUT arguments
 * @param returnValue  function return value, or {@code null} for procedures
 * @param cursorResult rows fetched from the first {@code SYS_REFCURSOR} OUT or
 *                     return parameter (each row a label-to-value map), capped
 *                     by {@code oracle.mcp.max-rows}; {@code null} when the call
 *                     produced no cursor
 */
public record PlsqlCallResult(
		Map<String, Object> outParams,
		Object returnValue,
		List<Map<String, Object>> cursorResult
) {
}
