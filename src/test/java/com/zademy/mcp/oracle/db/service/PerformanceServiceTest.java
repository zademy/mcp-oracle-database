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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PerformanceService}. The security-relevant behaviour
 * is {@link PerformanceService#listTopSql}, which interpolates {@code orderBy}
 * into the SQL string — it must come from an allow-list. The remaining tests
 * pin parameter binding and the list/scalar routing.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceServiceTest {

	@Mock
	private OracleDataAccess db;

	private PerformanceService service;

	@BeforeEach
	void setUp() {
		service = new PerformanceService(db);
	}

	@Nested
	@DisplayName("listTopSql — orderBy allow-list (security)")
	class ListTopSqlOrderby {

		@ParameterizedTest(name = "[{index}] orderBy=\"{0}\" -> ORDER BY {1}")
		@CsvSource({
				"buffer_gets,    buffer_gets",
				"disk_reads,     disk_reads",
				"disk,           disk_reads",
				"elapsed,        elapsed_time",
				"elapsed_time,   elapsed_time",
				"executions,     executions",
				"exec,           executions",
				"cpu_time,       cpu_time",
				"cpu,            cpu_time"
		})
		void knownAliasesMapToAllowListedColumns(String orderBy, String expectedColumn) {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listTopSql(orderBy, 5);

			String sql = captureSql();
			assertThat(sql).contains("ORDER BY " + expectedColumn + " DESC");
		}

		@Test
		void unknownOrderByFallsBackToBufferGets() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listTopSql("definitely-not-a-column", 5);

			String sql = captureSql();
			assertThat(sql).contains("ORDER BY buffer_gets DESC");
			assertThat(sql).doesNotContain("definitely-not-a-column");
		}

		@Test
		void nullOrderByFallsBackToBufferGets() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listTopSql(null, 5);

			assertThat(captureSql()).contains("ORDER BY buffer_gets DESC");
		}

		@Test
		void injectionAttemptInOrderByIsNeutralised() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			// A crafted orderBy must never reach the SQL verbatim.
			service.listTopSql("buffer_gets; DROP TABLE v$sql--", 5);

			String sql = captureSql();
			assertThat(sql).contains("ORDER BY buffer_gets DESC");
			assertThat(sql).doesNotContain("DROP TABLE");
			assertThat(sql).doesNotContain("--");
		}

		@Test
		void limitIsBoundAsNamedParameter() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listTopSql("cpu", 42);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), params.capture());
			assertThat(params.getValue()).containsEntry("limit", 42);
		}

		private String captureSql() {
			ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
			verify(db).queryForList(sql.capture(), anyMap());
			return sql.getValue();
		}
	}

	@Nested
	@DisplayName("parameter binding")
	class Binding {

		@Test
		void listActiveSessionsBindsUsernameAndSid() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listActiveSessions("SCOTT", 42);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), params.capture());
			assertThat(params.getValue())
					.containsEntry("username", "SCOTT")
					.containsEntry("sid", 42);
		}

		@Test
		void nullFiltersBindAsNullInsteadOfThrowing() {
			// Regression: listActiveSessions used Map.of, which NPEs on null values.
			// Optional filters must bind NULL so (:x IS NULL OR ...) matches all.
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			service.listActiveSessions(null, null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
			verify(db).queryForList(anyString(), params.capture());
			assertThat(params.getValue())
					.containsEntry("username", null)
					.containsEntry("sid", null);
		}
	}

	@Nested
	@DisplayName("routing / result shaping")
	class Routing {

		@Test
		void getSessionSqlTextConcatenatesPiecesInOrder() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of(
					Map.of("sql_text", "SELECT "),
					Map.of("sql_text", "* FROM emp")));

			assertThat(service.getSessionSqlText(7, 1)).isEqualTo("SELECT * FROM emp");
		}

		@Test
		void getSessionSqlTextReturnsEmptyStringWhenNoRows() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			assertThat(service.getSessionSqlText(7, 1)).isEmpty();
		}

		@Test
		void getInstanceInfoReturnsFirstRowOrEmptyMap() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of(
					Map.of("instance_name", "ORCL")));

			Map<String, Object> info = service.getInstanceInfo();

			assertThat(info).containsEntry("instance_name", "ORCL");
		}

		@Test
		void getInstanceInfoReturnsEmptyMapWhenViewEmpty() {
			when(db.queryForList(anyString(), anyMap())).thenReturn(List.of());

			assertThat(service.getInstanceInfo()).isEmpty();
		}
	}
}
