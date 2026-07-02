package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.model.IndexSuggestion;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IndexAdvisorService}: the pure analysis helpers plus
 * Oracle error propagation on the user-SQL paths ({@code suggestIndex},
 * {@code runSqlTuningAdvisor}).
 * <p>
 * Safety is enforced by the least-privilege Oracle user; the service forwards
 * the SQL verbatim and propagates any Oracle error.
 * No real database: {@link OracleDataAccess} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class IndexAdvisorServiceTest {

	@Mock
	private OracleDataAccess db;

	private IndexAdvisorService service;

	@BeforeEach
	void setUp() {
		service = new IndexAdvisorService(db);
	}

	private static Map<String, Object> row(String owner, String table, String op, String opts,
											long cost, String accessPred, String filterPred) {
		Map<String, Object> r = new HashMap<>();
		r.put("object_owner", owner);
		r.put("object_name", table);
		r.put("operation", op);
		r.put("options", opts);
		r.put("cost", cost);
		if (accessPred != null) r.put("access_predicates", accessPred);
		else r.put("access_predicates", null);
		if (filterPred != null) r.put("filter_predicates", filterPred);
		else r.put("filter_predicates", null);
		return r;
	}

	private static Map<String, Object> stat(String col, long numDistinct) {
		Map<String, Object> s = new HashMap<>();
		s.put("column_name", col);
		s.put("num_distinct", numDistinct);
		return s;
	}

	@Nested
	@DisplayName("findFullScanCandidate")
	class FindCandidate {

		@Test
		void nullOrEmptyReturnsNull() {
			assertThat(IndexAdvisorService.findFullScanCandidate(null, null)).isNull();
			assertThat(IndexAdvisorService.findFullScanCandidate(List.of(), null)).isNull();
		}

		@Test
		void noFullScanReturnsNull() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "EMP", "TABLE ACCESS", "BY INDEX ROWID", 10, null, null),
					row("HR", "DEPT", "INDEX", "RANGE SCAN", 2, null, null));
			assertThat(IndexAdvisorService.findFullScanCandidate(rows, null)).isNull();
		}

		@Test
		void singleFullScanIsReturned() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "EMP", "TABLE ACCESS", "FULL", 100, null, "\"DEPTNO\"=10"));
			Map<String, Object> c = IndexAdvisorService.findFullScanCandidate(rows, null);
			assertThat(c).isNotNull();
			assertThat(c.get("object_name")).isEqualTo("EMP");
		}

		@Test
		void picksHighestCostFullScan() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "EMP", "TABLE ACCESS", "FULL", 50, null, null),
					row("HR", "BIG", "TABLE ACCESS", "FULL", 900, null, null));
			Map<String, Object> c = IndexAdvisorService.findFullScanCandidate(rows, null);
			assertThat(c.get("object_name")).isEqualTo("BIG");
		}

		@Test
		void targetTableFilterIsCaseInsensitive() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "EMP", "TABLE ACCESS", "FULL", 999, null, null),
					row("HR", "DEPT", "TABLE ACCESS", "FULL", 10, null, null));
			Map<String, Object> c = IndexAdvisorService.findFullScanCandidate(rows, "dept");
			assertThat(c.get("object_name")).isEqualTo("DEPT");
		}

		@Test
		void targetTableAbsentReturnsNull() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "EMP", "TABLE ACCESS", "FULL", 100, null, null));
			assertThat(IndexAdvisorService.findFullScanCandidate(rows, "NOPE")).isNull();
		}

		@Test
		void tiedOrAbsentCostPicksDeterministicallyByObjectName() {
			var rows = List.<Map<String, Object>>of(
					row("HR", "ZZZ", "TABLE ACCESS", "FULL", 0, null, null),
					row("HR", "AAA", "TABLE ACCESS", "FULL", 0, null, null));
			Map<String, Object> c1 = IndexAdvisorService.findFullScanCandidate(rows, null);
			Map<String, Object> c2 = IndexAdvisorService.findFullScanCandidate(rows, null);
			assertThat(c1.get("object_name")).isEqualTo(c2.get("object_name")).isEqualTo("ZZZ");
		}
	}

	@Nested
	@DisplayName("buildSuggestion")
	class Build {

		@Test
		void singlePredicateColumnProducesDdl() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100, null, "\"DEPTNO\"=10");
			List<Map<String, Object>> stats = List.of(stat("DEPTNO", 10), stat("ENAME", 5000));

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, stats);

			assertThat(s.hasCandidates()).isTrue();
			assertThat(s.table()).isEqualTo("EMP");
			assertThat(s.predicateColumns()).containsExactly("DEPTNO");
			assertThat(s.recommendedColumns()).containsExactly("DEPTNO");
			assertThat(s.recommendedDdl()).isEqualTo("CREATE INDEX \"HR\".\"IX_EMP_DEPTNO\" ON \"HR\".\"EMP\" (\"DEPTNO\")");
			assertThat(s.reasoning()).contains("num_distinct=10");
		}

		@Test
		void leadingColumnIsMostSelectiveRegardlessOfPredicateOrder() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100,
					null, "\"JOB\"='CLERK' AND \"DEPTNO\"=10");
			List<Map<String, Object>> stats = List.of(stat("DEPTNO", 10), stat("JOB", 5));

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, stats);

			assertThat(s.recommendedColumns()).startsWith("DEPTNO");
			assertThat(s.recommendedDdl()).contains("\"DEPTNO\", \"JOB\"");
		}

		@Test
		void accessAndFilterPredicatesAreBothMined() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100,
					"\"EMPNO\"=1", "\"DEPTNO\"=10");
			List<Map<String, Object>> stats = List.of(stat("EMPNO", 1000), stat("DEPTNO", 10));

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, stats);

			assertThat(s.predicateColumns()).containsExactlyInAnyOrder("EMPNO", "DEPTNO");
			assertThat(s.recommendedColumns()).startsWith("EMPNO");
		}

		@Test
		void noPredicateColumnsYieldsNoDdlButFlagsCandidate() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100, null, null);

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, List.of());

			assertThat(s.hasCandidates()).isTrue();
			assertThat(s.recommendedDdl()).isNull();
			assertThat(s.predicateColumns()).isEmpty();
			assertThat(s.reasoning()).contains("no filter/join predicate columns");
		}

		@Test
		void missingStatsStillRecommendsWithZeroDistinct() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100, null, "\"DEPTNO\"=10");

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, List.of());

			assertThat(s.recommendedColumns()).containsExactly("DEPTNO");
			assertThat(s.reasoning()).contains("num_distinct=0");
		}

		@Test
		void duplicatePredicateColumnsAreDeduplicated() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100,
					"\"DEPTNO\"=10", "\"DEPTNO\"=10 AND \"DEPTNO\"=20");

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, List.of(stat("DEPTNO", 10)));

			assertThat(s.predicateColumns()).containsExactly("DEPTNO");
		}

		@Test
		void mixedCasePredicateColumnIsCaptured() {
			Map<String, Object> candidate = row("HR", "EMP", "TABLE ACCESS", "FULL", 100,
					null, "\"deptNo\"=1");
			List<Map<String, Object>> stats = List.of(stat("DEPTNO", 10));

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, stats);

			assertThat(s.predicateColumns()).containsExactly("deptNo");
			assertThat(s.recommendedDdl()).contains("\"deptNo\"");
		}

		@Test
		void longIndexNameIsTruncatedToFitOracleLimit() {
			String longTable = "T" + "A".repeat(110);
			String longCol = "C" + "B".repeat(40);
			Map<String, Object> candidate = row("HR", longTable, "TABLE ACCESS", "FULL", 100,
					null, "\"" + longCol + "\"=1");

			IndexSuggestion s = IndexAdvisorService.buildSuggestion(candidate, List.of(stat(longCol, 5)));

			assertThat(s.recommendedDdl()).isNotNull();
			int start = s.recommendedDdl().indexOf("\"HR\".\"") + "\"HR\".\"".length();
			int end = s.recommendedDdl().indexOf("\"", start);
			String indexName = s.recommendedDdl().substring(start, end);
			assertThat(indexName.length()).isLessThanOrEqualTo(128);
			assertThat(indexName).startsWith("IX_");
		}
	}

	@Nested
	@DisplayName("Oracle error propagation (suggestIndex + runSqlTuningAdvisor)")
	class OracleDenial {

		@Test
		void suggestIndexPropagatesDataAccessException() {
			String sql = "SELECT * FROM emp";
			DataAccessException boom = new DataRetrievalFailureException("ORA-00942: table does not exist");
			org.mockito.Mockito.doThrow(boom).when(db).execute(anyString());

			assertThatThrownBy(() -> service.suggestIndex(sql, null))
					.isInstanceOf(DataAccessException.class)
					.hasMessageContaining("ORA-00942");
		}

		@Test
		void runSqlTuningAdvisorReturnsFailureMessageOnDataAccessException() {
			String sql = "SELECT * FROM emp";
			DataAccessException boom = new DataRetrievalFailureException("ORA-00942: table or view does not exist");
			when(db.queryForObject(anyString(), any(), any())).thenThrow(boom);

			String result = service.runSqlTuningAdvisor(sql);

			assertThat(result).contains("SQL Tuning Advisor failed");
		}
	}
}
