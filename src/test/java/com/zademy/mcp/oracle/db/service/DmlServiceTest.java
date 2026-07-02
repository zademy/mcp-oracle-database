package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.DmlDryRunResult;
import com.zademy.mcp.oracle.db.model.DmlPreviewResult;
import com.zademy.mcp.oracle.db.model.DmlResult;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DmlService}. Safety is enforced by the least-privilege
 * Oracle user; this test suite verifies the service delegates correctly to
 * {@link OracleDataAccess} and propagates Oracle errors.
 */
@ExtendWith(MockitoExtension.class)
class DmlServiceTest {

	@Mock
	private OracleDataAccess db;

	@Mock
	private PlatformTransactionManager txManager;

	@Mock
	private TransactionStatus txStatus;

	private DmlService service;

	@BeforeEach
	void setUp() {
		OracleMcpProperties props = new OracleMcpProperties(1000, 60, 10, null);
		service = new DmlService(db, props, txManager);
	}

	@Nested
	@DisplayName("executeDml — happy paths")
	class HappyPaths {

		@ParameterizedTest(name = "[{index}] {0} affecting {1} rows")
		@CsvSource({
				"INSERT INTO emp (empno) VALUES (1), 1",
				"UPDATE emp SET sal = 0, 14",
				"DELETE FROM emp WHERE empno = 7369, 1",
				"MERGE INTO emp USING dual ON (1 = 0) WHEN NOT MATCHED THEN INSERT (empno) VALUES (1), 0"
		})
		void writeStatementsReturnDetectedKindAndRowCount(String sql, int affected) {
			when(db.update(sql)).thenReturn(affected);

			DmlResult result = service.executeDml(sql);

			assertThat(result.rowsAffected()).isEqualTo(affected);
			assertThat(result.statementKind()).isUpperCase();
			assertThat(result.statementKind()).isNotBlank();
			verify(db).update(sql);
		}
	}

	@Test
	void dataAccessExceptionPropagates() {
		String sql = "UPDATE emp SET sal = 0";
		DataAccessException boom = new DataRetrievalFailureException("ORA-01031: insufficient privileges");
		when(db.update(sql)).thenThrow(boom);

		assertThatThrownBy(() -> service.executeDml(sql)).isSameAs(boom);
	}

	@Nested
	@DisplayName("previewDml — UPDATE/DELETE preview (non-executing)")
	class Preview {

		@Test
		void previewsUpdateWithoutExecutingIt() {
			String sql = "UPDATE emp SET sal = 0 WHERE empno = 7369";
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class))).thenReturn(1);
			when(db.queryForList(startsWith("SELECT * FROM emp"))).thenReturn(List.of(
					Map.of("EMPNO", 7369, "SAL", 800)));

			DmlPreviewResult result = service.previewDml(sql);

			assertThat(result.detectedKind()).isEqualTo("UPDATE");
			assertThat(result.table()).contains("emp");
			assertThat(result.affectedRowCount()).isEqualTo(1);
			assertThat(result.sampleCount()).isEqualTo(1);
			assertThat(result.sampleColumns()).contains("EMPNO");
			assertThat(result.note()).contains("NOT executed");
			verify(db, never()).update(anyString());
		}

		@Test
		void previewsDeleteWithoutExecutingIt() {
			String sql = "DELETE FROM emp WHERE deptno = 10";
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class))).thenReturn(3);
			when(db.queryForList(startsWith("SELECT * FROM emp"))).thenReturn(List.of());

			DmlPreviewResult result = service.previewDml(sql);

			assertThat(result.detectedKind()).isEqualTo("DELETE");
			assertThat(result.affectedRowCount()).isEqualTo(3);
			assertThat(result.sampleCount()).isZero();
			verify(db, never()).update(anyString());
		}

		@Test
		void refusesInsertPreview() {
			String sql = "INSERT INTO emp (empno) VALUES (1)";

			assertThatThrownBy(() -> service.previewDml(sql))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("execute_dml_preview does not support INSERT");

			verify(db, never()).update(anyString());
		}

		@Test
		void refusesMergePreview() {
			String sql = "MERGE INTO emp USING dual ON (1 = 0) WHEN NOT MATCHED THEN INSERT (empno) VALUES (1)";

			assertThatThrownBy(() -> service.previewDml(sql))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("does not support MERGE");

			verify(db, never()).update(anyString());
		}

		@Test
		void previewRejectsSelect() {
			String sql = "SELECT * FROM emp";

			assertThatThrownBy(() -> service.previewDml(sql))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("execute_dml_preview only supports UPDATE or DELETE");

			verify(db, never()).update(anyString());
		}

		@Test
		void oracleDenialPropagatesDuringPreview() {
			String sql = "UPDATE emp SET sal = 0 WHERE empno = 7369";
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class)))
					.thenThrow(new DataRetrievalFailureException("ORA-00942: table does not exist"));

			assertThatThrownBy(() -> service.previewDml(sql))
					.isInstanceOf(DataAccessException.class)
					.hasMessageContaining("ORA-00942");
		}
	}

	@Nested
	@DisplayName("rollbackFirstDml — execute then roll back")
	class RollbackFirst {

		@Test
		void executesThenRollsBackAndReportsRows() {
			String sql = "UPDATE emp SET sal = 0 WHERE empno = 7369";
			when(txManager.getTransaction(any())).thenReturn(txStatus);
			when(db.update(sql)).thenReturn(1);

			DmlDryRunResult result = service.rollbackFirstDml(sql);

			assertThat(result.detectedKind()).isEqualTo("UPDATE");
			assertThat(result.rowsAffected()).isEqualTo(1);
			assertThat(result.rolledBack()).isTrue();
			assertThat(result.note()).contains("rolled back");
			verify(db).update(sql);
			verify(txStatus).setRollbackOnly();
		}

		@Test
		void rollbackFirstHandlesMerge() {
			String sql = "MERGE INTO emp t USING dual ON (t.empno = 1) WHEN MATCHED THEN UPDATE SET t.sal = 0";
			when(txManager.getTransaction(any())).thenReturn(txStatus);
			when(db.update(sql)).thenReturn(1);

			DmlDryRunResult result = service.rollbackFirstDml(sql);

			assertThat(result.detectedKind()).isEqualTo("MERGE");
			assertThat(result.rowsAffected()).isEqualTo(1);
			assertThat(result.rolledBack()).isTrue();
			verify(db).update(sql);
			verify(txStatus).setRollbackOnly();
		}

		@Test
		void oracleDenialPropagatesDuringRollbackFirst() {
			String sql = "INSERT INTO emp (empno) VALUES (1)";
			when(txManager.getTransaction(any())).thenReturn(txStatus);
			when(db.update(sql)).thenThrow(
					new DataRetrievalFailureException("ORA-01031: insufficient privileges"));

			assertThatThrownBy(() -> service.rollbackFirstDml(sql))
					.isInstanceOf(DataAccessException.class)
					.hasMessageContaining("ORA-01031");
		}
	}
}
