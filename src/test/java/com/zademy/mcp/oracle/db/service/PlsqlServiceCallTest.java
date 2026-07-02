package com.zademy.mcp.oracle.db.service;

import oracle.jdbc.OracleTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the PL/SQL call helpers in {@link PlsqlService}. No
 * database and no Spring context: these cover call-string assembly, the
 * Oracle&rarr;JDBC type map and the rejection of JDBC-incompatible PL/SQL types
 * on Oracle 19c. The binding/execution path is exercised end-to-end by the
 * Tier-2 e2e harness against a real Oracle instance.
 */
class PlsqlServiceCallTest {

	@Nested
	@DisplayName("buildCallSql")
	class BuildCallSql {

		@Test
		@DisplayName("procedure with two args: { call \"S\".\"P\"(?, ?) }")
		void procedureWithArgs() {
			String sql = PlsqlService.buildCallSql("S", "P", 2, false);
			assertThat(sql).isEqualTo("{ call \"S\".\"P\"(?, ?) }");
		}

		@Test
		@DisplayName("function with one arg: { ? = call \"S\".\"F\"(?) }")
		void functionWithArg() {
			String sql = PlsqlService.buildCallSql("S", "F", 1, true);
			assertThat(sql).isEqualTo("{ ? = call \"S\".\"F\"(?) }");
		}

		@Test
		@DisplayName("package subprogram is qualified in three parts")
		void packageSubprogram() {
			String sql = PlsqlService.buildCallSql("S", "PKG.PROC", 3, false);
			assertThat(sql).isEqualTo("{ call \"S\".\"PKG\".\"PROC\"(?, ?, ?) }");
		}

		@Test
		@DisplayName("procedure with no args emits empty placeholder list")
		void noArgs() {
			assertThat(PlsqlService.buildCallSql("S", "P", 0, false))
					.isEqualTo("{ call \"S\".\"P\"() }");
			assertThat(PlsqlService.buildCallSql("S", "F", 0, true))
					.isEqualTo("{ ? = call \"S\".\"F\"() }");
		}
	}

	@Nested
	@DisplayName("jdbcType")
	class JdbcType {

		@Test
		@DisplayName("numeric family maps to Types.NUMERIC")
		void numericFamily() {
			assertThat(PlsqlService.jdbcType("NUMBER")).isEqualTo(Types.NUMERIC);
			assertThat(PlsqlService.jdbcType("PLS_INTEGER")).isEqualTo(Types.NUMERIC);
			assertThat(PlsqlService.jdbcType("BINARY_FLOAT")).isEqualTo(Types.NUMERIC);
		}

		@Test
		@DisplayName("character family maps to Types.VARCHAR")
		void characterFamily() {
			assertThat(PlsqlService.jdbcType("VARCHAR2")).isEqualTo(Types.VARCHAR);
			assertThat(PlsqlService.jdbcType("CHAR")).isEqualTo(Types.VARCHAR);
			assertThat(PlsqlService.jdbcType("NVARCHAR2")).isEqualTo(Types.VARCHAR);
		}

		@Test
		@DisplayName("date/timestamp/lob types map to their JDBC codes")
		void dateAndLobs() {
			assertThat(PlsqlService.jdbcType("DATE")).isEqualTo(Types.DATE);
			assertThat(PlsqlService.jdbcType("TIMESTAMP")).isEqualTo(Types.TIMESTAMP);
			assertThat(PlsqlService.jdbcType("TIMESTAMP(6)")).isEqualTo(Types.TIMESTAMP);
			assertThat(PlsqlService.jdbcType("CLOB")).isEqualTo(Types.CLOB);
			assertThat(PlsqlService.jdbcType("BLOB")).isEqualTo(Types.BLOB);
		}

		@Test
		@DisplayName("REF CURSOR maps to OracleTypes.CURSOR")
		void refCursor() {
			assertThat(PlsqlService.jdbcType("REF CURSOR")).isEqualTo(OracleTypes.CURSOR);
			assertThat(PlsqlService.jdbcType("CURSOR")).isEqualTo(OracleTypes.CURSOR);
		}

		@Test
		@DisplayName("unknown scalar type falls back to Types.VARCHAR")
		void unknownFallsBackToVarchar() {
			assertThat(PlsqlService.jdbcType("BINARY_DOUBLE")).isEqualTo(Types.NUMERIC);
			assertThat(PlsqlService.jdbcType("ROWID")).isEqualTo(Types.VARCHAR);
		}
	}

	@Nested
	@DisplayName("requireSupportedType")
	class RequireSupportedType {

		@Test
		@DisplayName("scalar SQL types and REF CURSOR are accepted")
		void acceptsScalars() {
			PlsqlService.requireSupportedType("NUMBER", "x");
			PlsqlService.requireSupportedType("VARCHAR2", "x");
			PlsqlService.requireSupportedType("REF CURSOR", "x");
			PlsqlService.requireSupportedType(null, "x");
			PlsqlService.requireSupportedType("  ", "x");
		}

		@Test
		@DisplayName("BOOLEAN is rejected with wrapper guidance")
		void rejectsBoolean() {
			assertThatThrownBy(() -> PlsqlService.requireSupportedType("BOOLEAN", "argument 'FLAG'"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("BOOLEAN")
					.hasMessageContaining("wrapper");
		}

		@Test
		@DisplayName("RECORD, TABLE, VARRAY, OBJECT and PL/SQL ... are rejected")
		void rejectsCompositeTypes() {
			assertThatThrownBy(() -> PlsqlService.requireSupportedType("PL/SQL RECORD", "x"))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> PlsqlService.requireSupportedType("TABLE OF NUMBER", "x"))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> PlsqlService.requireSupportedType("VARRAY", "x"))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> PlsqlService.requireSupportedType("OBJECT", "x"))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("parseQualifiedName")
	class ParseQualifiedName {

		@Test
		@DisplayName("simple name yields a single validated part")
		void simpleName() {
			assertThat(PlsqlService.parseQualifiedName("PROC")).containsExactly("PROC");
		}

		@Test
		@DisplayName("package.subprogram yields two validated parts")
		void packageAndSubprogram() {
			assertThat(PlsqlService.parseQualifiedName("PKG.PROC")).containsExactly("PKG", "PROC");
		}

		@Test
		@DisplayName("invalid identifier is rejected")
		void invalidIdentifier() {
			assertThatThrownBy(() -> PlsqlService.parseQualifiedName("bad name"))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
