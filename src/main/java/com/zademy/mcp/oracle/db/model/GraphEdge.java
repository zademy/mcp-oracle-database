package com.zademy.mcp.oracle.db.model;

/**
 * A single directed relationship in the schema graph.
 *
 * @param fromObject the source object (child table, dependent view/procedure)
 * @param fromType   Oracle object type of the source (TABLE, VIEW, PROCEDURE…)
 * @param toObject   the target object (parent table, referenced object)
 * @param toType     Oracle object type of the target
 * @param edgeType   relationship kind: {@code "FK"} or {@code "DEPENDS_ON"}
 * @param label      optional label (constraint name or dependency detail)
 */
public record GraphEdge(
		String fromObject,
		String fromType,
		String toObject,
		String toType,
		String edgeType,
		String label) {
}
