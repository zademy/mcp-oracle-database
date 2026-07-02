package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.model.SchemaGraph;
import com.zademy.mcp.oracle.db.service.GraphService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools that expose schema relationship graphs (foreign-key and
 * object-dependency). Each tool returns a {@link SchemaGraph} containing the
 * raw edge list and a Mermaid diagram string.
 */
@Component
public class GraphTools {

	private final GraphService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the graph service that queries the data dictionary
	 */
	public GraphTools(GraphService service) {
		this.service = service;
	}

	/**
	 * Builds the foreign-key graph for a schema, optionally scoped to one table.
	 *
	 * @param schema schema to inspect
	 * @param table  optional table filter (null/blank = all FKs in schema)
	 * @return a {@link SchemaGraph} with child&rarr;parent edges and Mermaid text
	 */
	@McpTool(name = "get_fk_graph",
			description = "Build a foreign-key relationship graph for a schema or a single table. Returns directed edges (child→parent) and a Mermaid diagram string.")
	public SchemaGraph getFkGraph(
			@McpToolParam(description = "Schema (owner) to inspect.", required = true) String schema,
			@McpToolParam(description = "Optional table name to scope the graph. Leave blank for the entire schema.", required = false) String table) {
		return service.fkGraph(schema, table);
	}

	/**
	 * Builds the object-dependency graph for a schema, optionally scoped to one
	 * object name or type.
	 *
	 * @param schema schema to inspect
	 * @param name   optional object-name filter
	 * @param type   optional object-type filter (TABLE, VIEW, PROCEDURE, …)
	 * @return a {@link SchemaGraph} with dependent&rarr;referenced edges and Mermaid text
	 */
	@McpTool(name = "get_dependency_graph",
			description = "Build an object-dependency graph for a schema using all_dependencies. Returns directed edges (dependent→referenced) filtered by optional name/type, plus a Mermaid diagram string.")
	public SchemaGraph getDependencyGraph(
			@McpToolParam(description = "Schema (owner) to inspect.", required = true) String schema,
			@McpToolParam(description = "Optional object name to scope the graph.", required = false) String name,
			@McpToolParam(description = "Optional object-type filter (TABLE, VIEW, PROCEDURE, PACKAGE, …).", required = false) String type) {
		return service.dependencyGraph(schema, name, type);
	}
}
