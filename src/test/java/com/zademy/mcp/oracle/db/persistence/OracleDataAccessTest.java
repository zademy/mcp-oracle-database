package com.zademy.mcp.oracle.db.persistence;

import com.zademy.mcp.oracle.db.config.AuditProperties;
import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OracleDataAccess}. The class is a thin delegation layer
 * over {@link JdbcTemplate} / {@link NamedParameterJdbcTemplate}; these tests
 * pin the pass-through contract, the {@code null}-params-becomes-empty-map rule,
 * and exception propagation.
 */
@ExtendWith(MockitoExtension.class)
class OracleDataAccessTest {

	@Mock
	private JdbcTemplate jdbc;

	@Mock
	private NamedParameterJdbcTemplate namedJdbc;

	private OracleDataAccess dao;

	@BeforeEach
	void setUp() {
		dao = new OracleDataAccess(jdbc, namedJdbc,
				new OracleMcpProperties(500, 30, 10, new AuditProperties(false, "./mcp-audit-logs")));
	}

	@Nested
	@DisplayName("maxRows")
	class MaxRows {
		@Test
		void readsCapFromProperties() {
			assertThat(dao.maxRows()).isEqualTo(500);
		}
	}

	@Nested
	@DisplayName("raw SQL path (AI-provided)")
	class RawSql {

		@Test
		void queryForListDelegatesToJdbc() {
			List<Map<String, Object>> rows = List.of(Map.of("A", 1));
			when(jdbc.queryForList("SELECT 1")).thenReturn(rows);

			assertThat(dao.queryForList("SELECT 1")).isSameAs(rows);
		}

		@Test
		void updateDelegatesToJdbcAndReturnsCount() {
			when(jdbc.update("DELETE FROM x")).thenReturn(3);

			assertThat(dao.update("DELETE FROM x")).isEqualTo(3);
		}

		@Test
		void executeDelegatesToJdbc() {
			dao.execute("CREATE INDEX i ON x (c)");

			verify(jdbc).execute("CREATE INDEX i ON x (c)");
		}
	}

	@Nested
	@DisplayName("named-parameter path")
	class Named {

		@Test
		void queryForListPassesParamsThrough() {
			Map<String, Object> params = Map.of("name", "EMP");
			List<Map<String, Object>> rows = List.of(Map.of("c", 1));
			when(namedJdbc.queryForList("SELECT 1 WHERE n = :name", params)).thenReturn(rows);

			assertThat(dao.queryForList("SELECT 1 WHERE n = :name", params)).isSameAs(rows);
		}

		@Test
		void queryForListWithNullParamsBecomesEmptyMap() {
			when(namedJdbc.queryForList(eq("SELECT 1"), anyMap())).thenReturn(List.of());

			dao.queryForList("SELECT 1", null);

			// The wrapper must never pass null to Spring; it substitutes an empty map.
			@SuppressWarnings("unchecked")
			org.mockito.ArgumentCaptor<Map<String, Object>> captor =
					org.mockito.ArgumentCaptor.forClass(Map.class);
			verify(namedJdbc).queryForList(eq("SELECT 1"), captor.capture());
			assertThat(captor.getValue()).isEmpty();
		}

		@Test
		void updateWithNullParamsBecomesEmptyMap() {
			when(namedJdbc.update(eq("UPDATE x"), anyMap())).thenReturn(0);

			assertThat(dao.update("UPDATE x", null)).isZero();
		}

		@Test
		void queryForObjectPassesParamsAndType() {
			when(namedJdbc.queryForObject(eq("SELECT count(*) FROM dual"), anyMap(), eq(String.class)))
					.thenReturn("1");

			assertThat(dao.queryForObject("SELECT count(*) FROM dual", Map.of(), String.class))
					.isEqualTo("1");
		}
	}

	@Nested
	@DisplayName("parameterless scalar")
	class Scalar {
		@Test
		void queryForObjectDelegatesToJdbc() {
			when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

			assertThat(dao.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("exceptions propagate unchanged")
	void dataAccessExceptionPropagates() {
		DataAccessException boom = new QueryTimeoutException("ORA- timeout");
		when(jdbc.queryForList("SELECT 1")).thenThrow(boom);

		assertThatThrownBy(() -> dao.queryForList("SELECT 1")).isSameAs(boom);
	}
}
