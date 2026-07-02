package com.zademy.mcp.oracle.db.service;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DdlService}. The service assembles SQL from validated
 * identifiers and routes it through {@code executeChecked} to {@link OracleDataAccess}.
 * These tests pin the <b>exact</b> assembled SQL so identifier-quoting or
 * injection regressions surface immediately. Safety for structural statements
 * is enforced by the least-privilege Oracle user (no DDL/DCL privileges).
 */
@ExtendWith(MockitoExtension.class)
class DdlServiceTest {

	@Mock
	private OracleDataAccess db;

	private DdlService service;

	@BeforeEach
	void setUp() {
		service = new DdlService(db);
	}

	@Nested
	@DisplayName("commentOnTable / commentOnColumn — exact SQL")
	class Comments {

		@Test
		void commentOnTableAssemblesQuotedIdentifierAndEscapedLiteral() {
			String message = service.commentOnTable("SCOTT", "EMP", "nice table");

			verify(db).execute("COMMENT ON TABLE \"SCOTT\".\"EMP\" IS 'nice table'");
			assertThat(message).isEqualTo("Comment applied to table SCOTT.EMP");
		}

		@Test
		void commentWithEmbeddedSingleQuoteIsDoubled() {
			service.commentOnTable("SCOTT", "EMP", "O'Brien");

			verify(db).execute("COMMENT ON TABLE \"SCOTT\".\"EMP\" IS 'O''Brien'");
		}

		@Test
		void nullCommentBecomesSqlNullLiteral() {
			service.commentOnTable("SCOTT", "EMP", null);

			verify(db).execute("COMMENT ON TABLE \"SCOTT\".\"EMP\" IS NULL");
		}

		@Test
		void commentOnColumnAssemblesThreePartName() {
			String message = service.commentOnColumn("SCOTT", "EMP", "SAL", "monthly");

			verify(db).execute("COMMENT ON COLUMN \"SCOTT\".\"EMP\".\"SAL\" IS 'monthly'");
			assertThat(message).isEqualTo("Comment applied to column SCOTT.EMP.SAL");
		}
	}

	@Nested
	@DisplayName("createOrReplaceView")
	class Views {

		@Test
		void rejectsBodyThatDoesNotStartWithSelect() {
			assertThatThrownBy(() -> service.createOrReplaceView("S", "V", "DELETE FROM emp"))
					.isInstanceOf(IllegalArgumentException.class);

			verify(db, never()).execute(anyString());
		}

		@Test
		void createViewPassesAssembledSqlToDataAccess() {
			service.createOrReplaceView("SCOTT", "EMP_VIEW", "SELECT * FROM emp");

			verify(db).execute("CREATE OR REPLACE VIEW \"SCOTT\".\"EMP_VIEW\" AS SELECT * FROM emp");
		}
	}

	@Nested
	@DisplayName("createSynonym")
	class Synonyms {

		@Test
		void privateSynonymPassesAssembledSqlToDataAccess() {
			service.createSynonym("S", "SYN", "TS", "TN", false);

			verify(db).execute("CREATE SYNONYM \"S\".\"SYN\" FOR \"TS\".\"TN\"");
		}

		@Test
		void publicSynonymPassesAssembledSqlToDataAccess() {
			service.createSynonym("S", "SYN", "TS", "TN", true);

			verify(db).execute("CREATE PUBLIC SYNONYM \"S\".\"SYN\" FOR \"TS\".\"TN\"");
		}
	}

	@Nested
	@DisplayName("createSequence")
	class Sequences {

		@Test
		void fullOptionsPassAssembledSqlToDataAccess() {
			service.createSequence("S", "SEQ", 1L, 2L, 20L, true, true);

			verify(db).execute("CREATE SEQUENCE \"S\".\"SEQ\" START WITH 1 INCREMENT BY 2 CACHE 20 CYCLE ORDER");
		}

		@Test
		void minimalOptionsPassAssembledSqlToDataAccess() {
			service.createSequence("S", "SEQ", null, null, null, false, false);

			verify(db).execute("CREATE SEQUENCE \"S\".\"SEQ\" NOCACHE NOCYCLE NOORDER");
		}

		@Test
		void rejectsZeroIncrementBy() {
			assertThatThrownBy(() -> service.createSequence("S", "SEQ", null, 0L, null, false, false))
					.isInstanceOf(IllegalArgumentException.class);

			verify(db, never()).execute(anyString());
		}
	}

	@Nested
	@DisplayName("createIndex")
	class Indexes {

		@Test
		void nonUniqueIndexPassesAssembledSqlToDataAccess() {
			service.createIndex("S", "IDX", "TS", "T", List.of("C1", "C2"), false);

			verify(db).execute("CREATE INDEX \"S\".\"IDX\" ON \"TS\".\"T\" (\"C1\", \"C2\")");
		}

		@Test
		void uniqueIndexPassesAssembledSqlToDataAccess() {
			service.createIndex("S", "UIDX", "TS", "T", List.of("PK"), true);

			verify(db).execute("CREATE UNIQUE INDEX \"S\".\"UIDX\" ON \"TS\".\"T\" (\"PK\")");
		}

		@Test
		void rejectsEmptyColumnList() {
			assertThatThrownBy(() -> service.createIndex("S", "IDX", "TS", "T", List.of(), false))
					.isInstanceOf(IllegalArgumentException.class);

			verify(db, never()).execute(anyString());
		}
	}

	@Nested
	@DisplayName("createMviewLog")
	class MviewLogs {

		@Test
		void primaryKeyAndRowidWithNewValuesPassesToDataAccess() {
			service.createMviewLog("S", "T", true, true, true);

			verify(db).execute(
					"CREATE MATERIALIZED VIEW LOG ON \"S\".\"T\" WITH PRIMARY KEY, ROWID INCLUDING NEW VALUES");
		}

		@Test
		void rowidOnlyPassesToDataAccess() {
			service.createMviewLog("S", "T", true, false, false);

			verify(db).execute("CREATE MATERIALIZED VIEW LOG ON \"S\".\"T\" WITH ROWID");
		}

		@Test
		void rejectsWhenNeitherRowidNorPrimaryKey() {
			assertThatThrownBy(() -> service.createMviewLog("S", "T", false, false, false))
					.isInstanceOf(IllegalArgumentException.class);

			verify(db, never()).execute(anyString());
		}
	}

	@Test
	void invalidIdentifierRejectedBeforeReachingDatabase() {
		assertThatThrownBy(() -> service.commentOnTable("EV\"IL", "T", "x"))
				.isInstanceOf(IllegalArgumentException.class);

		verify(db, never()).execute(anyString());
	}

	@Test
	void oracleDenialPropagatesAsDataAccessException() {
		DataAccessException boom = new DataRetrievalFailureException("ORA-01031: insufficient privileges");
		org.mockito.Mockito.doThrow(boom).when(db).execute(anyString());

		assertThatThrownBy(() -> service.commentOnTable("SCOTT", "EMP", "test"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-01031");
	}
}
