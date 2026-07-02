package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.model.QueryResult;
import com.zademy.mcp.oracle.db.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QueryTools}. The tool's only logic is the
 * try/catch that converts a {@link DataAccessException} into an
 * {@code "Oracle error: ..."} string and {@link IllegalArgumentException}
 * into its message; these tests pin that contract.
 */
@ExtendWith(MockitoExtension.class)
class QueryToolsTest {

	@Mock
	private QueryService service;

	private QueryTools tools;

	@BeforeEach
	void setUp() {
		tools = new QueryTools(service);
	}

	@Nested
	@DisplayName("runQuery")
	class RunQuery {

		@Test
		void returnsQueryResultOnSuccess() {
			QueryResult result = new QueryResult(List.of("A"), List.of(), 0, false, 500, 0, 500);
			when(service.runQuery("SELECT 1", null, null)).thenReturn(result);

			Object out = tools.runQuery("SELECT 1", null, null);

			assertThat(out).isSameAs(result);
		}

		@Test
		void dataAccessExceptionIsReturnedAsOracleErrorString() {
			when(service.runQuery("SELECT * FROM restricted", null, null))
					.thenThrow(new DataRetrievalFailureException("ORA-01031: insufficient privileges"));

			Object out = tools.runQuery("SELECT * FROM restricted", null, null);

			assertThat(out)
					.isInstanceOf(String.class)
					.asString()
					.startsWith("Oracle error: ")
					.contains("ORA-01031");
		}

		@Test
		void illegalArgumentExceptionIsReturnedAsMessage() {
			when(service.runQuery("bad sql", null, null))
					.thenThrow(new IllegalArgumentException("invalid SQL"));

			Object out = tools.runQuery("bad sql", null, null);

			assertThat(out).isEqualTo("invalid SQL");
		}
	}

	@Nested
	@DisplayName("getSampleData")
	class GetSampleData {

		@Test
		void nullRowsIsTranslatedToZero() {
			QueryResult result = new QueryResult(List.of(), List.of(), 0, false, 500, 0, 500);
			when(service.sampleData("S", "T", 0)).thenReturn(result);

			Object out = tools.getSampleData("S", "T", null);

			assertThat(out).isSameAs(result);
			verify(service).sampleData("S", "T", 0);
		}
	}
}
