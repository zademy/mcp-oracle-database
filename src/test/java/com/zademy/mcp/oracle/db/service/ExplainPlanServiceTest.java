package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExplainPlanService}.
 * <p>
 * Safety is enforced by the least-privilege Oracle user; the service forwards
 * the SQL verbatim to {@link OracleDataAccess} and propagates any Oracle error.
 * No real database: {@link OracleDataAccess} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class ExplainPlanServiceTest {

	@Mock
	private OracleDataAccess db;

	private ExplainPlanService service;

	@BeforeEach
	void setUp() {
		service = new ExplainPlanService(db);
	}

	@Nested
	@DisplayName("allowed SQL flows through to EXPLAIN PLAN")
	class HappyPath {

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {
				"SELECT * FROM emp WHERE empno = 1",
				"INSERT INTO emp (empno, ename) VALUES (1, 'SMITH')",
				"UPDATE emp SET ename = 'JONES' WHERE empno = 1",
				"DELETE FROM emp WHERE empno = 1",
				"MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.v = s.v"
		})
		void allowedSqlReturnsRenderedPlan(String sql) {
			when(db.queryForList(anyString(), any())).thenReturn(
					List.of(Map.of("plan_table_output", "PLAN_ROW")));
			org.mockito.Mockito.doReturn(1).when(db).update(anyString(), any());

			String plan = service.explain(sql);

			assertThat(plan).isEqualTo("PLAN_ROW");
		}

		@Test
		void emptyPlanReturnsEmptyString() {
			when(db.queryForList(anyString(), any())).thenReturn(List.of());
			org.mockito.Mockito.doReturn(0).when(db).update(anyString(), any());

			assertThat(service.explain("SELECT * FROM emp")).isEmpty();
		}
	}

	@Test
	@DisplayName("Oracle denial during EXPLAIN PLAN propagates as DataAccessException")
	void oracleDenialPropagates() {
		String sql = "SELECT * FROM restricted_table";
		DataAccessException boom = new DataRetrievalFailureException("ORA-00942: table does not exist");
		when(db.queryForList(anyString(), any())).thenThrow(boom);

		assertThatThrownBy(() -> service.explain(sql))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-00942");
	}
}
