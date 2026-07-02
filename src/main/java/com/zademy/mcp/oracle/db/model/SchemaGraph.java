package com.zademy.mcp.oracle.db.model;

import java.util.List;

/**
 * Directed graph of schema relationships (foreign keys or object dependencies).
 *
 * @param schema    the schema the graph was built for
 * @param focus     optional object name the graph was scoped to, or {@code null}
 * @param graphType {@code "FK"} (foreign-key) or {@code "DEPENDENCY"} (object deps)
 * @param edges     ordered list of directed relationships
 * @param mermaid   Mermaid {@code graph LR} diagram text for visual rendering
 */
public record SchemaGraph(
		String schema,
		String focus,
		String graphType,
		List<GraphEdge> edges,
		String mermaid) {
}
