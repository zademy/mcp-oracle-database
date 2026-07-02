package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.model.DmlResult;
import com.zademy.mcp.oracle.db.service.DmlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DmlTools}. Pins the try/catch that converts
 * {@link DataAccessException} into an {@code "Oracle error: ..."} string
 * and {@link IllegalArgumentException} into its message.
 */
@ExtendWith(MockitoExtension.class)
class DmlToolsTest {

	@Mock
	private DmlService service;

	private DmlTools tools;

	@BeforeEach
	void setUp() {
		tools = new DmlTools(service);
	}

	@Test
	@DisplayName("executeDml returns DmlResult on success")
	void returnsDmlResultOnSuccess() {
		DmlResult result = new DmlResult("INSERT", 1);
		when(service.executeDml("INSERT INTO emp VALUES (1)")).thenReturn(result);

		Object out = tools.executeDml("INSERT INTO emp VALUES (1)");

		assertThat(out).isSameAs(result);
	}

	@Test
	@DisplayName("DataAccessException is returned as Oracle error string")
	void dataAccessExceptionIsReturnedAsOracleErrorString() {
		when(service.executeDml("DROP TABLE emp"))
				.thenThrow(new DataRetrievalFailureException("ORA-01031: insufficient privileges"));

		Object out = tools.executeDml("DROP TABLE emp");

		assertThat(out)
				.isInstanceOf(String.class)
				.asString()
				.startsWith("Oracle error: ")
				.contains("ORA-01031");
	}

	@Test
	@DisplayName("IllegalArgumentException is returned as its message")
	void illegalArgumentExceptionIsReturnedAsMessage() {
		when(service.previewDml("INSERT INTO emp VALUES (1)"))
				.thenThrow(new IllegalArgumentException("execute_dml_preview does not support INSERT"));

		Object out = tools.executeDmlPreview("INSERT INTO emp VALUES (1)");

		assertThat(out)
				.isInstanceOf(String.class)
				.isEqualTo("execute_dml_preview does not support INSERT");
	}
}
