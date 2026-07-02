package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.audit.AuditLogReader;
import com.zademy.mcp.oracle.db.service.MetadataService;
import com.zademy.mcp.oracle.db.service.PlsqlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OracleResourcesTest {

	@Mock
	private MetadataService metadata;
	@Mock
	private PlsqlService plsql;
	@Mock
	private AuditLogReader auditReader;

	private OracleResources resources;

	@BeforeEach
	void setUp() {
		resources = new OracleResources(metadata, plsql, auditReader);
	}

	@Nested
	class TableDescription {

		@Test
		void formatsColumns() {
			Map<String, Object> col1 = new LinkedHashMap<>();
			col1.put("column_name", "ID");
			col1.put("data_type", "NUMBER(10)");
			col1.put("nullable", "N");
			col1.put("data_default", "");
			col1.put("comments", "Primary key");

			Map<String, Object> col2 = new LinkedHashMap<>();
			col2.put("column_name", "NAME");
			col2.put("data_type", "VARCHAR2(255)");
			col2.put("nullable", "Y");
			col2.put("data_default", "");
			col2.put("comments", "");

			when(metadata.describeTable("HR", "EMPLOYEES"))
					.thenReturn(List.of(col1, col2));

			String result = resources.tableDescription("HR", "EMPLOYEES");

			assertThat(result).contains("Table: HR.EMPLOYEES");
			assertThat(result).contains("ID");
			assertThat(result).contains("NUMBER(10)");
			assertThat(result).contains("NOT NULL");
			assertThat(result).contains("NAME");
			assertThat(result).contains("VARCHAR2(255)");
			assertThat(result).contains("NULL");
			assertThat(result).contains("Primary key");
		}

		@Test
		void emptyColumnsReturnsMessage() {
			when(metadata.describeTable("HR", "NONEXISTENT"))
					.thenReturn(List.of());

			String result = resources.tableDescription("HR", "NONEXISTENT");

			assertThat(result).startsWith("(No columns found");
			assertThat(result).contains("HR.NONEXISTENT");
		}

		@Test
		void databaseErrorReturnsMessage() {
			when(metadata.describeTable(any(), any()))
					.thenThrow(new DataAccessResourceFailureException("ORA-00942: table or view does not exist"));

			String result = resources.tableDescription("HR", "BAD");

			assertThat(result).contains("Database error");
			assertThat(result).contains("ORA-00942");
		}
	}

	@Nested
	class ObjectDdl {

		@Test
		void returnsDdl() {
			when(metadata.getDdlAuto("HR", "EMPLOYEES"))
					.thenReturn("  CREATE TABLE \"HR\".\"EMPLOYEES\"\n    (\"ID\" NUMBER(10));  ");

			String result = resources.objectDdl("HR", "EMPLOYEES");

			assertThat(result).startsWith("CREATE TABLE");
			assertThat(result).contains("\"HR\".\"EMPLOYEES\"");
		}

		@Test
		void nullDdlReturnsNotFound() {
			when(metadata.getDdlAuto("HR", "GHOST"))
					.thenReturn(null);

			String result = resources.objectDdl("HR", "GHOST");

			assertThat(result).startsWith("(Object not found");
			assertThat(result).contains("HR.GHOST");
		}

		@Test
		void databaseErrorReturnsMessage() {
			when(metadata.getDdlAuto(any(), any()))
					.thenThrow(new DataAccessResourceFailureException("ORA-31603"));

			String result = resources.objectDdl("HR", "BAD");

			assertThat(result).contains("Database error");
			assertThat(result).contains("ORA-31603");
		}
	}

	@Nested
	class PlsqlSource {

		@Test
		void returnsSource() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("schema", "HR");
			result.put("name", "MY_PROC");
			result.put("type", "PROCEDURE");
			result.put("arguments", List.of());
			result.put("source", "PROCEDURE my_proc AS\nBEGIN\n  NULL;\nEND;\n/");

			when(plsql.describe("HR", "MY_PROC", null))
					.thenReturn(result);

			String output = resources.plsqlSource("HR", "MY_PROC");

			assertThat(output).startsWith("-- HR.MY_PROC (PROCEDURE)");
			assertThat(output).contains("PROCEDURE my_proc");
			assertThat(output).contains("BEGIN");
		}

		@Test
		void emptySourceReturnsMessage() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("source", "");

			when(plsql.describe(any(), any(), eq(null)))
					.thenReturn(result);

			String output = resources.plsqlSource("HR", "GHOST");

			assertThat(output).startsWith("(No PL/SQL source found");
			assertThat(output).contains("HR.GHOST");
		}

		@Test
		void databaseErrorReturnsMessage() {
			when(plsql.describe(any(), any(), eq(null)))
					.thenThrow(new DataAccessResourceFailureException("ORA-04043"));

			String output = resources.plsqlSource("HR", "BAD");

			assertThat(output).contains("Database error");
		}
	}

	@Nested
	class AuditSession {

		@Test
		void returnsLogContent() {
			when(auditReader.readSession("a1b2c3d4"))
					.thenReturn(Optional.of("===== audit entry 1 =====\nSQL: SELECT 1\n"));

			String result = resources.auditSession("a1b2c3d4");

			assertThat(result).contains("audit entry 1");
			assertThat(result).contains("SELECT 1");
		}

		@Test
		void currentSessionDelegatesToCurrentSessionId() {
			when(auditReader.currentSessionId()).thenReturn("xyz12345");
			when(auditReader.readSession("xyz12345"))
					.thenReturn(Optional.of("current session log"));

			String result = resources.auditSession("current");

			assertThat(result).isEqualTo("current session log");
		}

		@Test
		void emptyAuditReturnsFallbackMessage() {
			when(auditReader.readSession("nonexistent"))
					.thenReturn(Optional.empty());

			String result = resources.auditSession("nonexistent");

			assertThat(result).contains("No audit log found");
			assertThat(result).contains("nonexistent");
		}
	}
}
