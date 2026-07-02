package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.audit.AuditLogReader;
import com.zademy.mcp.oracle.db.service.MetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OracleCompletions}. Verifies that schema, table, and
 * object completions filter by prefix and cap at {@code MAX_SUGGESTIONS}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OracleCompletions")
class OracleCompletionsTest {

	@Mock
	private MetadataService metadata;
	@Mock
	private AuditLogReader auditReader;

	private OracleCompletions completions;

	@BeforeEach
	void setUp() {
		completions = new OracleCompletions(metadata, auditReader);
	}

	// ------------------------------------------------------------------ Schema completions

	@Nested
	@DisplayName("schema completions")
	class SchemaCompletions {

		@Test
		@DisplayName("filter schemas by prefix")
		void filterByPrefix() {
			when(metadata.listSchemas()).thenReturn(List.of("HR", "OE", "PM", "IX", "SH"));

			List<String> result = completions.completeTableUriSchema("H");

			assertThat(result).containsExactly("HR");
		}

		@Test
		@DisplayName("case-insensitive prefix matching")
		void caseInsensitive() {
			when(metadata.listSchemas()).thenReturn(List.of("HR", "OE", "SH"));

			List<String> result = completions.completeExplainSchemaSchema("h");

			assertThat(result).containsExactly("HR");
		}

		@Test
		@DisplayName("returns all matching when prefix is empty")
		void emptyPrefix() {
			when(metadata.listSchemas()).thenReturn(List.of("HR", "OE", "SH"));

			List<String> result = completions.completeDdlUriSchema("");

			assertThat(result).containsExactly("HR", "OE", "SH");
		}

		@Test
		@DisplayName("caps at 20 suggestions")
		void capsAtTwenty() {
			List<String> many = java.util.stream.Stream.iterate('A', c -> (char) (c + 1))
					.limit(25)
					.map(String::valueOf)
					.toList();
			when(metadata.listSchemas()).thenReturn(many);

			List<String> result = completions.completeDataQualitySchema("");

			assertThat(result).hasSize(20);
		}

		@Test
		@DisplayName("returns empty list when DB throws")
		void emptyOnDbError() {
			when(metadata.listSchemas()).thenThrow(new RuntimeException("DB down"));

			List<String> result = completions.completeTableUriSchema("H");

			assertThat(result).isEmpty();
		}
	}

	// ------------------------------------------------------------------ Table completions

	@Nested
	@DisplayName("table completions")
	class TableCompletions {

		@Test
		@DisplayName("filter tables by prefix")
		void filterByPrefix() {
			when(metadata.currentSchema()).thenReturn("MCP_USER");
			when(metadata.listTables("MCP_USER", null)).thenReturn(List.of(
					tableRow("EMPLOYEES"),
					tableRow("DEPARTMENTS"),
					tableRow("EMP_SALARY")));

			List<String> result = completions.completeTableUriTable("EMP");

			assertThat(result).containsExactly("EMPLOYEES", "EMP_SALARY");
		}

		@Test
		@DisplayName("returns empty when no match")
		void noMatch() {
			when(metadata.currentSchema()).thenReturn("MCP_USER");
			when(metadata.listTables("MCP_USER", null)).thenReturn(List.of(
					tableRow("ORDERS")));

			List<String> result = completions.completeExplainSchemaTable("EMP");

			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("handles null current schema gracefully")
		void nullCurrentSchema() {
			when(metadata.currentSchema()).thenReturn("");

			List<String> result = completions.completeDataQualityTable("EMP");

			assertThat(result).isEmpty();
		}
	}

	// ------------------------------------------------------------------ Object completions

	@Nested
	@DisplayName("object completions")
	class ObjectCompletions {

		@Test
		@DisplayName("filter objects by prefix")
		void filterByPrefix() {
			when(metadata.searchObjects(isNull(), isNull())).thenReturn(List.of(
					objRow("PKG_BILLING"),
					objRow("FN_CALC_TAX"),
					objRow("PKG_ORDERS")));

			List<String> result = completions.completeDdlUriObject("PKG");

			assertThat(result).containsExactly("PKG_BILLING", "PKG_ORDERS");
		}

		@Test
		@DisplayName("case-insensitive")
		void caseInsensitive() {
			when(metadata.searchObjects(isNull(), isNull())).thenReturn(List.of(
					objRow("PROC_UPDATE_STATUS")));

			List<String> result = completions.completePlsqlUriObject("proc");

			assertThat(result).containsExactly("PROC_UPDATE_STATUS");
		}
	}

	// ------------------------------------------------------------------ Audit session completions

	@Nested
	@DisplayName("audit session completions")
	class AuditSessionCompletions {

		@Test
		@DisplayName("always includes 'current'")
		void includesCurrent() {
			when(auditReader.listSessionIds()).thenReturn(List.of());

			List<String> result = completions.completeAuditSessionId("");

			assertThat(result).contains("current");
		}

		@Test
		@DisplayName("filters by prefix")
		void filterByPrefix() {
			when(auditReader.listSessionIds()).thenReturn(List.of("a1b2c3d4", "e5f6g7h8"));

			List<String> result = completions.completeAuditSessionId("a");

			assertThat(result).contains("current", "a1b2c3d4");
			assertThat(result).doesNotContain("e5f6g7h8");
		}

		@Test
		@DisplayName("'current' matches current prefix")
		void currentPrefix() {
			when(auditReader.listSessionIds()).thenReturn(List.of("a1b2c3d4"));

			List<String> result = completions.completeAuditSessionId("cur");

			assertThat(result).containsExactly("current");
		}

		@Test
		@DisplayName("handles audit disabled")
		void auditDisabled() {
			when(auditReader.listSessionIds()).thenThrow(new RuntimeException("disabled"));

			List<String> result = completions.completeAuditSessionId("");

			assertThat(result).containsExactly("current");
		}
	}

	// ------------------------------------------------------------------ Helpers

	private static Map<String, Object> tableRow(String name) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("TABLE_NAME", name);
		return row;
	}

	private static Map<String, Object> objRow(String name) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("OBJECT_NAME", name);
		return row;
	}
}
