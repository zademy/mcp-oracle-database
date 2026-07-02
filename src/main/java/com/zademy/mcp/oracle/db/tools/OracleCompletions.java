package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.audit.AuditLogReader;
import com.zademy.mcp.oracle.db.service.MetadataService;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Completions that auto-complete resource URI variables and prompt
 * arguments.
 *
 * <p><b>Protocol scope.</b> Per the MCP specification, completions apply only
 * to prompt arguments and resource URI template variables — not to
 * {@code @McpTool} parameters.
 *
 * <p><b>Schema context.</b> When completing a {@code {table}} or {@code {object}}
 * variable, the already-resolved {@code {schema}} value is not available to the
 * method. Table/object completions therefore query {@code USER_*} views (the
 * connected user's own schema), which is the most common target. Schema-name
 * completions use {@code ALL_USERS} via {@link MetadataService#listSchemas()}.
 *
 * <p>All completions return at most 20 suggestions to keep the autocomplete
 * dropdown manageable.
 */
@Component
public class OracleCompletions {

	private static final int MAX_SUGGESTIONS = 20;

	private final MetadataService metadata;
	private final AuditLogReader auditReader;

	public OracleCompletions(MetadataService metadata, AuditLogReader auditReader) {
		this.metadata = metadata;
		this.auditReader = auditReader;
	}

	// ================================================================
	// Schema-name completions (shared helper)
	// ================================================================

	private List<String> completeSchemaNames(String prefix) {
		String upper = prefix == null ? "" : prefix.toUpperCase();
		List<String> result = new ArrayList<>();
		for (String s : safeListSchemas()) {
			if (s.toUpperCase().startsWith(upper)) {
				result.add(s);
				if (result.size() >= MAX_SUGGESTIONS) {
					break;
				}
			}
		}
		return result;
	}

	private List<String> safeListSchemas() {
		try {
			return metadata.listSchemas();
		} catch (Exception e) {
			return List.of();
		}
	}

	// ================================================================
	// Table-name completions (shared helper)
	// ================================================================

	private List<String> completeTableNames(String prefix) {
		String upper = prefix == null ? "" : prefix.toUpperCase();
		List<Map<String, Object>> tables = safeListUserTables();
		List<String> result = new ArrayList<>();
		for (Map<String, Object> row : tables) {
			String name = String.valueOf(row.getOrDefault("TABLE_NAME", ""));
			if (name.toUpperCase().startsWith(upper)) {
				result.add(name);
				if (result.size() >= MAX_SUGGESTIONS) {
					break;
				}
			}
		}
		return result;
	}

	private List<Map<String, Object>> safeListUserTables() {
		try {
			return metadata.listTables(metadata.currentSchema(), null);
		} catch (Exception e) {
			return List.of();
		}
	}

	// ================================================================
	// Object-name completions (shared helper)
	// ================================================================

	private List<String> completeObjectNames(String prefix) {
		String upper = prefix == null ? "" : prefix.toUpperCase();
		List<Map<String, Object>> objects = safeListObjects();
		List<String> result = new ArrayList<>();
		for (Map<String, Object> row : objects) {
			String name = String.valueOf(row.getOrDefault("OBJECT_NAME", ""));
			if (name.toUpperCase().startsWith(upper)) {
				result.add(name);
				if (result.size() >= MAX_SUGGESTIONS) {
					break;
				}
			}
		}
		return result;
	}

	private List<Map<String, Object>> safeListObjects() {
		try {
			return metadata.searchObjects(null, null);
		} catch (Exception e) {
			return List.of();
		}
	}

	// ================================================================
	// Resource URI completions
	// ================================================================

	// --- oracle://schema/{schema}/table/{table} ---

	@McpComplete(uri = "oracle://schema/{schema}/table/{table}")
	public List<String> completeTableUriSchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(uri = "oracle://schema/{schema}/table/{table}")
	public List<String> completeTableUriTable(String table) {
		return completeTableNames(table);
	}

	// --- oracle://schema/{schema}/object/{object}/ddl ---

	@McpComplete(uri = "oracle://schema/{schema}/object/{object}/ddl")
	public List<String> completeDdlUriSchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(uri = "oracle://schema/{schema}/object/{object}/ddl")
	public List<String> completeDdlUriObject(String object) {
		return completeObjectNames(object);
	}

	// --- oracle://schema/{schema}/plsql/{object} ---

	@McpComplete(uri = "oracle://schema/{schema}/plsql/{object}")
	public List<String> completePlsqlUriSchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(uri = "oracle://schema/{schema}/plsql/{object}")
	public List<String> completePlsqlUriObject(String object) {
		return completeObjectNames(object);
	}

	// --- oracle://audit/session/{id} ---

	@McpComplete(uri = "oracle://audit/session/{id}")
	public List<String> completeAuditSessionId(String id) {
		String prefix = id == null ? "" : id.toLowerCase();
		List<String> result = new ArrayList<>();
		result.add("current");
		for (String sid : safeListSessionIds()) {
			if (sid.startsWith(prefix)) {
				result.add(sid);
				if (result.size() >= MAX_SUGGESTIONS) {
					break;
				}
			}
		}
		return result;
	}

	private List<String> safeListSessionIds() {
		try {
			return auditReader.listSessionIds();
		} catch (Exception e) {
			return List.of();
		}
	}

	// ================================================================
	// Prompt argument completions
	// ================================================================

	// --- explain-schema(schema, table) ---

	@McpComplete(prompt = "explain-schema")
	public List<String> completeExplainSchemaSchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(prompt = "explain-schema")
	public List<String> completeExplainSchemaTable(String table) {
		return completeTableNames(table);
	}

	// --- debug-invalid-plsql(schema, object) ---

	@McpComplete(prompt = "debug-invalid-plsql")
	public List<String> completeDebugPlsqlSchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(prompt = "debug-invalid-plsql")
	public List<String> completeDebugPlsqlObject(String object) {
		return completeObjectNames(object);
	}

	// --- data-quality-audit(schema, table) ---

	@McpComplete(prompt = "data-quality-audit")
	public List<String> completeDataQualitySchema(String schema) {
		return completeSchemaNames(schema);
	}

	@McpComplete(prompt = "data-quality-audit")
	public List<String> completeDataQualityTable(String table) {
		return completeTableNames(table);
	}
}
