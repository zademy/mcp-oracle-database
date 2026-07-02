package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.model.GraphEdge;
import com.zademy.mcp.oracle.db.model.SchemaGraph;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds relationship graphs (foreign-key and object-dependency) for a schema.
 * Each method returns a {@link SchemaGraph} containing the raw edges and a
 * Mermaid {@code graph LR} diagram string.
 */
@Service
public class GraphService {

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer for dictionary queries
	 */
	public GraphService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Builds the foreign-key graph for a schema, optionally scoped to a single
	 * table. Each edge represents a child-table &rarr; parent-table FK link.
	 *
	 * @param schema the schema to inspect (validated and double-quoted)
	 * @param table  optional table filter; {@code null}/blank returns all FKs
	 * @return a {@link SchemaGraph} with edges and Mermaid text
	 */
	public SchemaGraph fkGraph(String schema, String table) {
		String validatedSchema = SqlIdentifiers.validate(schema);
		Map<String, Object> params = Map.of("schema", validatedSchema);

		String tableFilter = "";
		if (table != null && !table.isBlank()) {
			String validatedTable = SqlIdentifiers.validate(table);
			params = Map.of("schema", validatedSchema, "table", validatedTable);
			tableFilter = " AND (c.table_name = :table OR p.table_name = :table)";
		}

		List<Map<String, Object>> rows = db.queryForList("""
				SELECT c.table_name AS child_table,
				       c.constraint_name AS fk_name,
				       p.owner AS parent_owner,
				       p.table_name AS parent_table
				FROM all_constraints c
				JOIN all_constraints p
				  ON c.r_owner = p.owner AND c.r_constraint_name = p.constraint_name
				WHERE c.owner = :schema
				  AND c.constraint_type = 'R'
				""" + tableFilter + """
				ORDER BY c.table_name, p.table_name""", params);

		List<GraphEdge> edges = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String childTable = str(row.get("child_table"));
			String parentTable = str(row.get("parent_table"));
			String fkName = str(row.get("fk_name"));
			if (childTable == null || parentTable == null) continue;
			edges.add(new GraphEdge(
					validatedSchema + "." + childTable, "TABLE",
					str(row.get("parent_owner")) + "." + parentTable, "TABLE",
					"FK", fkName));
		}
		return new SchemaGraph(validatedSchema, blankToNull(table), "FK",
				edges, mermaidFk(edges));
	}

	/**
	 * Builds the object-dependency graph for a schema, optionally scoped to a
	 * single object. Edges represent {@code DEPENDS_ON} relationships from
	 * {@code all_dependencies}.
	 *
	 * @param schema the schema to inspect (validated)
	 * @param name   optional object-name filter; {@code null}/blank returns all
	 * @param type   optional object-type filter (TABLE, VIEW, PROCEDURE, …)
	 * @return a {@link SchemaGraph} with edges and Mermaid text
	 */
	public SchemaGraph dependencyGraph(String schema, String name, String type) {
		String validatedSchema = SqlIdentifiers.validate(schema);
		StringBuilder sql = new StringBuilder("""
				SELECT name, type, referenced_name, referenced_type
				FROM all_dependencies
				WHERE owner = :schema""");
		Map<String, Object> params = new java.util.HashMap<>(Map.of("schema", validatedSchema));

		if (name != null && !name.isBlank()) {
			String validatedName = SqlIdentifiers.validate(name);
			sql.append(" AND name = :name");
			params.put("name", validatedName);
		}
		if (type != null && !type.isBlank()) {
			sql.append(" AND type = :type");
			params.put("type", type.toUpperCase());
		}
		sql.append(" ORDER BY name, referenced_name");

		List<Map<String, Object>> rows = db.queryForList(sql.toString(), params);

		List<GraphEdge> edges = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String objName = str(row.get("name"));
			String objType = str(row.get("type"));
			String refName = str(row.get("referenced_name"));
			String refType = str(row.get("referenced_type"));
			if (objName == null || refName == null) continue;
			if (objName.equals(refName)) continue;
			edges.add(new GraphEdge(
					validatedSchema + "." + objName, objType,
					validatedSchema + "." + refName, refType,
					"DEPENDS_ON", null));
		}
		return new SchemaGraph(validatedSchema, blankToNull(name), "DEPENDENCY",
				edges, mermaidDependency(edges));
	}

	private static String mermaidFk(List<GraphEdge> edges) {
		if (edges.isEmpty()) return "graph LR\n  %% no foreign keys found";
		StringBuilder sb = new StringBuilder("graph LR\n");
		TreeSet<String> seen = new TreeSet<>();
		for (GraphEdge e : edges) {
			String child = shortName(e.fromObject());
			String parent = shortName(e.toObject());
			String key = child + "|" + parent;
			if (seen.add(key)) {
				sb.append("  ").append(safeId(child))
						.append(" -->|").append(safeLabel(e.label())).append("| ")
						.append(safeId(parent)).append("\n");
			}
		}
		return sb.toString().stripTrailing();
	}

	private static String mermaidDependency(List<GraphEdge> edges) {
		if (edges.isEmpty()) return "graph LR\n  %% no dependencies found";
		StringBuilder sb = new StringBuilder("graph LR\n");
		TreeSet<String> seen = new TreeSet<>();
		for (GraphEdge e : edges) {
			String from = shortName(e.fromObject());
			String to = shortName(e.toObject());
			String key = from + "|" + to;
			if (seen.add(key)) {
				sb.append("  ").append(safeId(from))
						.append(" --> ").append(safeId(to)).append("\n");
			}
		}
		return sb.toString().stripTrailing();
	}

	private static String shortName(String qualified) {
		int idx = qualified.indexOf('.');
		return idx > 0 ? qualified.substring(idx + 1) : qualified;
	}

	private static String safeId(String name) {
		return name.replaceAll("[^A-Za-z0-9_]", "_");
	}

	private static String safeLabel(String label) {
		return label == null ? "fk" : label.replaceAll("[^A-Za-z0-9_]", "_");
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
