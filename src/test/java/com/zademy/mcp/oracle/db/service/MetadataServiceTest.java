package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetadataService}. Representative methods plus the
 * {@code likePattern} boundaries (the only logic the service owns; everything
 * else is a straight parameterised query). Verifies the named-parameter map and
 * the SQL passed to the data-access layer.
 */
@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

	@Mock
	private OracleDataAccess db;

	private MetadataService service;

	@BeforeEach
	void setUp() {
		service = new MetadataService(db);
	}

	@Nested
	@DisplayName("listSchemas")
	class ListSchemas {

		@Test
		void extractsUsernamesFromRows() {
			when(db.queryForList(anyString(), any())).thenReturn(List.of(
					row("username", "SCOTT"), row("username", "HR")));

			List<String> schemas = service.listSchemas();

			assertThat(schemas).containsExactly("SCOTT", "HR");
		}
	}

	@Nested
	@DisplayName("listTables — likePattern boundaries")
	class ListTablesPattern {

		@ParameterizedTest(name = "[{index}] input \"{0}\" -> LIKE pattern {1}")
		@CsvSource({
				// null/blank -> match-all
				",             '%'",
				"'',           '%'",
				"'   ',        '%'",
				// plain substring -> wrapped uppercased
				"'emp',        '%EMP%'",
				// explicit wildcards are honoured as-is (uppercased)
				"'%E%',        '%E%'",
				"'a_b',        'A_B'",
				// mixed wildcard + substring still treated as a real pattern
				"'EMP_%',      'EMP_%'"
		})
		void patternIsComputedFromInput(String input, String expectedPattern) {
			when(db.queryForList(anyString(), any())).thenReturn(List.of());

			service.listTables("SCOTT", input);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), captor.capture());
			assertThat(captor.getValue().get("pattern")).isEqualTo(expectedPattern);
			assertThat(captor.getValue().get("schema")).isEqualTo("SCOTT");
		}

		@Test
		void nullSchemaBindsAsNullInsteadOfThrowing() {
			// Regression: listTables used Map.of, which NPEs on a null value.
			// Optional filters must bind NULL so (:schema IS NULL OR ...) matches all.
			when(db.queryForList(anyString(), any())).thenReturn(List.of());

			service.listTables(null, "EMP");

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), captor.capture());
			assertThat(captor.getValue())
					.containsEntry("schema", null)
					.containsEntry("pattern", "%EMP%");
		}
	}

	@Nested
	@DisplayName("describeTable / getDdl — bound parameters")
	class BoundParameters {

		@Test
		void describeTableBindsSchemaAndTable() {
			when(db.queryForList(anyString(), any())).thenReturn(List.of());

			service.describeTable("SCOTT", "EMP");

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), captor.capture());
			assertThat(captor.getValue()).containsEntry("schema", "SCOTT").containsEntry("table", "EMP");
		}

		@Test
		void getDdlBindsTypeNameAndSchemaAndCallsScalarPath() {
			when(db.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn("CREATE TABLE ...");

			String ddl = service.getDdl("SCOTT", "EMP", "TABLE");

			assertThat(ddl).isEqualTo("CREATE TABLE ...");
			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForObject(anyString(), captor.capture(), eq(String.class));
			assertThat(captor.getValue())
					.containsEntry("schema", "SCOTT")
					.containsEntry("name", "EMP")
					.containsEntry("type", "TABLE");
		}
	}

	@Nested
	@DisplayName("getSequenceInfo")
	class GetSequenceInfo {

		@Test
		void returnsFirstRowWhenSequenceExists() {
			when(db.queryForList(anyString(), any())).thenReturn(List.of(
					Map.of("sequence_name", "EMP_SEQ")));

			Map<String, Object> info = service.getSequenceInfo("SCOTT", "EMP_SEQ");

			assertThat(info).containsEntry("sequence_name", "EMP_SEQ");
			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), captor.capture());
			assertThat(captor.getValue())
					.containsEntry("schema", "SCOTT")
					.containsEntry("sequence", "EMP_SEQ");
		}

		@Test
		void returnsNullWhenSequenceMissing() {
			when(db.queryForList(anyString(), any())).thenReturn(List.of());

			assertThat(service.getSequenceInfo("SCOTT", "NOPE")).isNull();
		}
	}

	@Nested
	@DisplayName("tableExists")
	class TableExists {

		@Test
		void returnsTrueWhenCountPositive() {
			when(db.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

			assertThat(service.tableExists("SCOTT", "EMP")).isTrue();
		}

		@Test
		void returnsFalseWhenCountZero() {
			when(db.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

			assertThat(service.tableExists("SCOTT", "NOPE")).isFalse();
		}

		@Test
		void bindsSchemaAndTable() {
			when(db.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

			service.tableExists("SCOTT", "EMP");

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForObject(anyString(), captor.capture(), eq(Long.class));
			assertThat(captor.getValue())
					.containsEntry("schema", "SCOTT")
					.containsEntry("table", "EMP");
		}
	}

	@Test
	void dataAccessExceptionPropagates() {
		DataAccessException boom = new QueryTimeoutException("ORA- timeout");
		when(db.queryForList(anyString(), any())).thenThrow(boom);

		// Non-null args so the call reaches the data-access layer; the mock
		// throws and the exception must propagate unchanged.
		assertThatThrownBy(() -> service.listTables("SCOTT", "EMP")).isSameAs(boom);
	}

	private static Map<String, Object> row(String key, Object value) {
		return Map.of(key, value);
	}
}
