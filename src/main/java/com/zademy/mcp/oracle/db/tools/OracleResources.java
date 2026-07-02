package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.audit.AuditLogReader;
import com.zademy.mcp.oracle.db.service.MetadataService;
import com.zademy.mcp.oracle.db.service.PlsqlService;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP resources exposing Oracle schema content and audit logs as URI-addressable
 * text resources.
 *
 * <p>Four resource templates:
 * <ul>
 *   <li>{@code oracle://schema/{schema}/table/{table}} — column-level table description</li>
 *   <li>{@code oracle://schema/{schema}/object/{object}/ddl} — DDL source (auto-detected type)</li>
 *   <li>{@code oracle://schema/{schema}/plsql/{object}} — PL/SQL source code</li>
 *   <li>{@code oracle://audit/session/{id}} — per-session SQL audit log (inert when disabled)</li>
 * </ul>
 *
 * <p>All methods return plain-text {@code String} content. Database errors are
 * caught and returned as descriptive text so the MCP client always receives a
 * readable response rather than a protocol error.
 */
@Component
public class OracleResources {

	private final MetadataService metadata;
	private final PlsqlService plsql;
	private final AuditLogReader auditReader;

	public OracleResources(MetadataService metadata, PlsqlService plsql, AuditLogReader auditReader) {
		this.metadata = metadata;
		this.plsql = plsql;
		this.auditReader = auditReader;
	}

	@McpResource(
			uri = "oracle://schema/{schema}/table/{table}",
			name = "table-description",
			description = "Column-level description of an Oracle table or view (columns, types, nullability, defaults, comments)",
			mimeType = "text/plain"
	)
	public String tableDescription(String schema, String table) {
		try {
			List<Map<String, Object>> columns = metadata.describeTable(schema, table);
			if (columns.isEmpty()) {
				return "(No columns found for " + schema + "." + table + " — the object may not exist or is not accessible.)";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("Table: ").append(schema).append('.').append(table).append("\n\n");
			sb.append(String.format("%-30s %-30s %-8s %-20s %s%n",
					"Column", "Type", "Null?", "Default", "Comments"));
			sb.append("-".repeat(110)).append("\n");
			for (Map<String, Object> col : columns) {
				sb.append(String.format("%-30s %-30s %-8s %-20s %s%n",
						str(col.get("column_name")),
						str(col.get("data_type")),
						"Y".equals(str(col.get("nullable"))) ? "NULL" : "NOT NULL",
						str(col.get("data_default")).trim(),
						str(col.get("comments"))));
			}
			return sb.toString();
		} catch (DataAccessException e) {
			return "(Database error describing " + schema + "." + table + ": " + rootMessage(e) + ")";
		}
	}

	@McpResource(
			uri = "oracle://schema/{schema}/object/{object}/ddl",
			name = "object-ddl",
			description = "DDL source of any Oracle object (table, view, index, sequence, type, ...); type auto-detected",
			mimeType = "text/plain"
	)
	public String objectDdl(String schema, String object) {
		try {
			String ddl = metadata.getDdlAuto(schema, object);
			if (ddl == null) {
				return "(Object not found: " + schema + "." + object + ")";
			}
			return ddl.trim();
		} catch (DataAccessException e) {
			return "(Database error extracting DDL for " + schema + "." + object + ": " + rootMessage(e) + ")";
		}
	}

	@McpResource(
			uri = "oracle://schema/{schema}/plsql/{object}",
			name = "plsql-source",
			description = "PL/SQL source code (procedure, function, package, package body, type body)",
			mimeType = "text/plain"
	)
	public String plsqlSource(String schema, String object) {
		try {
			Map<String, Object> result = plsql.describe(schema, object, null);
			String source = str(result.get("source"));
			if (source.isBlank()) {
				return "(No PL/SQL source found for " + schema + "." + object + " — the object may not exist or is not a PL/SQL object.)";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("-- ").append(schema).append('.').append(object);
			String type = str(result.get("type"));
			if (!type.isBlank() && !"null".equalsIgnoreCase(type)) {
				sb.append(" (").append(type).append(")");
			}
			sb.append("\n\n");
			sb.append(source);
			return sb.toString();
		} catch (DataAccessException e) {
			return "(Database error reading PL/SQL source for " + schema + "." + object + ": " + rootMessage(e) + ")";
		}
	}

	@McpResource(
			uri = "oracle://audit/session/{id}",
			name = "audit-session",
			description = "Per-session SQL audit log (tool calls, SQL text, rows, durations). Use 'current' for the live session.",
			mimeType = "text/plain"
	)
	public String auditSession(String id) {
		try {
			String sessionId = "current".equalsIgnoreCase(id) ? auditReader.currentSessionId() : id;
			return auditReader.readSession(sessionId)
					.orElse("(No audit log found for session '" + id + "'. Audit logging may be disabled or no SQL has been executed yet.)");
		} catch (RuntimeException e) {
			return "(Unexpected error reading audit log for session '" + id + "': " + rootMessage(e) + ")";
		}
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String rootMessage(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}
}
