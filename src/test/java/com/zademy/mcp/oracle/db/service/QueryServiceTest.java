package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.QueryResult;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QueryService}. Safety is enforced by the least-privilege
 * Oracle user; this test suite verifies the service delegates correctly to
 * {@link OracleDataAccess} and propagates Oracle errors.
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

	@Mock
	private OracleDataAccess db;

	@Mock
	private OracleMcpProperties props;

	private QueryService service;

	@BeforeEach
	void setUp() {
		service = new QueryService(db, props);
	}

	@Nested
	@DisplayName("runQuery")
	class RunQuery {

		@Test
		void allowsSelectAndMapsRowsToResult() {
			String sql = "SELECT empno, ename FROM emp";
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("EMPNO", 7369);
			row.put("ENAME", "SMITH");
			when(db.queryForList(sql)).thenReturn(List.of(row));
			when(db.maxRows()).thenReturn(500);

			QueryResult result = service.runQuery(sql);

			assertThat(result.columns()).containsExactly("EMPNO", "ENAME");
			assertThat(result.rows()).hasSize(1);
			assertThat(result.rowCount()).isEqualTo(1);
			assertThat(result.truncated()).isFalse();
			assertThat(result.rowLimit()).isEqualTo(500);
			verify(db).queryForList(sql);
		}

		@Test
		void emptyResultYieldsEmptyColumnsAndRowCount() {
			String sql = "SELECT * FROM dual WHERE 1 = 0";
			when(db.queryForList(sql)).thenReturn(List.of());
			when(db.maxRows()).thenReturn(500);

			QueryResult result = service.runQuery(sql);

			assertThat(result.columns()).isEmpty();
			assertThat(result.rows()).isEmpty();
			assertThat(result.rowCount()).isZero();
			assertThat(result.truncated()).isFalse();
		}

		@Test
		void truncatedFlagTrueWhenResultSizeHitsRowCap() {
			String sql = "SELECT * FROM big";
			when(db.maxRows()).thenReturn(2);
			when(db.queryForList(sql)).thenReturn(List.of(
					Map.of("A", 1), Map.of("A", 2)));

			QueryResult result = service.runQuery(sql);

			assertThat(result.rowCount()).isEqualTo(2);
			assertThat(result.truncated()).isTrue();
			assertThat(result.rowLimit()).isEqualTo(2);
		}

		@Test
		void oracleDenialPropagatesAsDataAccessException() {
			String sql = "SELECT * FROM restricted_table";
			when(db.queryForList(sql)).thenThrow(
					new DataRetrievalFailureException("ORA-01031: insufficient privileges"));

			assertThatThrownBy(() -> service.runQuery(sql))
					.isInstanceOf(DataAccessException.class)
					.hasMessageContaining("ORA-01031");
		}
	}

	@Nested
	@DisplayName("runQuery — stateless offset/limit pagination")
	class Pagination {

		@Test
		void wrapsWithOffsetAndLimitWhenPaginating() {
			String sql = "SELECT empno FROM emp ORDER BY empno";
			String wrapped = "SELECT * FROM (" + sql + ") OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY";
			when(db.maxRows()).thenReturn(500);
			when(db.queryForList(wrapped)).thenReturn(List.of(Map.of("EMPNO", 1)));

			QueryResult result = service.runQuery(sql, 20, 10);

			assertThat(result.offset()).isEqualTo(20);
			assertThat(result.limit()).isEqualTo(10);
			assertThat(result.rowCount()).isEqualTo(1);
			verify(db).queryForList(wrapped);
		}

		@Test
		void limitIsCappedAtServerMaxRows() {
			String sql = "SELECT * FROM emp";
			int maxRows = 50;
			String wrapped = "SELECT * FROM (" + sql + ") OFFSET 10 ROWS FETCH NEXT " + maxRows + " ROWS ONLY";
			when(db.maxRows()).thenReturn(maxRows);
			when(db.queryForList(wrapped)).thenReturn(List.of());

			QueryResult result = service.runQuery(sql, 10, 9999);

			assertThat(result.limit()).isEqualTo(maxRows);
			verify(db).queryForList(wrapped);
		}

		@Test
		void doesNotWrapWhenOffsetZeroAndLimitAtCap() {
			String sql = "SELECT * FROM emp";
			when(db.maxRows()).thenReturn(500);
			when(db.queryForList(sql)).thenReturn(List.of(Map.of("A", 1)));

			QueryResult result = service.runQuery(sql, 0, 500);

			assertThat(result.offset()).isZero();
			assertThat(result.limit()).isEqualTo(500);
			verify(db).queryForList(sql);
		}

		@Test
		void negativeOffsetTreatedAsZero() {
			String sql = "SELECT * FROM emp";
			String wrapped = "SELECT * FROM (" + sql + ") OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY";
			when(db.maxRows()).thenReturn(500);
			when(db.queryForList(wrapped)).thenReturn(List.of());

			service.runQuery(sql, -3, 5);

			verify(db).queryForList(wrapped);
		}
	}

	@Nested
	@DisplayName("sampleData")
	class SampleData {

		@Test
		void buildsFetchFirstSqlAndDelegates() {
			when(db.queryForList(anyString())).thenReturn(List.of());
			when(db.maxRows()).thenReturn(500);

			service.sampleData("SCOTT", "EMP", 5);

			verify(db).queryForList(
					"SELECT * FROM \"SCOTT\".\"EMP\" FETCH FIRST 5 ROWS ONLY");
		}

		@Test
		void usesDefaultSampleRowsWhenRequestedCountIsZero() {
			when(props.defaultSampleRows()).thenReturn(25);
			when(db.queryForList(anyString())).thenReturn(List.of());
			when(db.maxRows()).thenReturn(500);

			service.sampleData("SCOTT", "EMP", 0);

			verify(db).queryForList(
					"SELECT * FROM \"SCOTT\".\"EMP\" FETCH FIRST 25 ROWS ONLY");
		}

		@Test
		void rejectsInvalidIdentifierBeforeTouchingDatabase() {
			assertThatThrownBy(() -> service.sampleData("EV\"IL", "EMP", 5))
					.isInstanceOf(IllegalArgumentException.class);

			verify(db, never()).queryForList(anyString());
		}
	}
}
