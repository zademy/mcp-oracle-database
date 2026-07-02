package com.zademy.mcp.oracle.db.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OraclePrompts}. Verifies that each prompt template
 * interpolates its arguments correctly and references the relevant MCP tools.
 *
 * <p>These tests are pure — no mocks needed since prompts are stateless
 * templates that do not call any service or database.
 */
@DisplayName("OraclePrompts")
class OraclePromptsTest {

	private final OraclePrompts prompts = new OraclePrompts(new SqlAuthoringRules());

	// ------------------------------------------------------------------ review-sql-performance

	@Nested
	@DisplayName("review-sql-performance")
	class ReviewSqlPerformance {

		@Test
		@DisplayName("interpolates the SQL and references performance tools")
		void interpolatesSqlAndReferencesTools() {
			String sql = "SELECT * FROM orders WHERE customer_id = 100";
			String result = prompts.reviewSqlPerformance(sql);

			assertThat(result).isNotBlank();
			assertThat(result).contains(sql);
			assertThat(result).contains("explain_plan");
			assertThat(result).contains("suggest_index");
			assertThat(result).contains("get_table_stats");
		}

		@Test
		@DisplayName("includes output-format section")
		void includesOutputFormat() {
			String result = prompts.reviewSqlPerformance("SELECT 1 FROM dual");

			assertThat(result).contains("Findings");
			assertThat(result).contains("Recommendations");
			assertThat(result).contains("Rewritten SQL");
		}
	}

	// ------------------------------------------------------------------ explain-schema

	@Nested
	@DisplayName("explain-schema")
	class ExplainSchema {

		@Test
		@DisplayName("full-schema mode when table is null")
		void fullSchemaModeWhenTableNull() {
			String result = prompts.explainSchema("HR", null);

			assertThat(result).isNotBlank();
			assertThat(result).contains("HR");
			assertThat(result).contains("list_tables");
			assertThat(result).contains("get_fk_graph");
			assertThat(result).doesNotContain("describe_table");
		}

		@Test
		@DisplayName("full-schema mode when table is blank")
		void fullSchemaModeWhenTableBlank() {
			String result = prompts.explainSchema("HR", "  ");

			assertThat(result).contains("list_tables");
			assertThat(result).doesNotContain("describe_table");
		}

		@Test
		@DisplayName("table-focused mode when table is provided")
		void tableFocusedModeWhenTableProvided() {
			String result = prompts.explainSchema("HR", "EMPLOYEES");

			assertThat(result).contains("HR");
			assertThat(result).contains("EMPLOYEES");
			assertThat(result).contains("describe_table");
			assertThat(result).contains("get_ddl");
			assertThat(result).contains("list_indexes");
			assertThat(result).contains("list_constraints");
			assertThat(result).doesNotContain("list_tables");
		}
	}

	// ------------------------------------------------------------------ debug-invalid-plsql

	@Nested
	@DisplayName("debug-invalid-plsql")
	class DebugInvalidPlsql {

		@Test
		@DisplayName("generic mode when object is null")
		void genericModeWhenObjectNull() {
			String result = prompts.debugInvalidPlsql("HR", null);

			assertThat(result).isNotBlank();
			assertThat(result).contains("HR");
			assertThat(result).contains("list_invalid_objects");
			assertThat(result).contains("get_plsql_errors");
			assertThat(result).contains("describe_plsql");
			assertThat(result).contains("all invalid objects");
		}

		@Test
		@DisplayName("focused mode when object is provided")
		void focusedModeWhenObjectProvided() {
			String result = prompts.debugInvalidPlsql("HR", "PKG_BILLING");

			assertThat(result).contains("HR");
			assertThat(result).contains("PKG_BILLING");
			assertThat(result).contains("get_plsql_errors");
			assertThat(result).contains("describe_plsql");
		}

		@Test
		@DisplayName("includes common PLS error codes")
		void includesCommonErrorCodes() {
			String result = prompts.debugInvalidPlsql("HR", null);

			assertThat(result).contains("PLS-00103");
			assertThat(result).contains("PLS-00201");
			assertThat(result).contains("ORA-06550");
		}
	}

	// ------------------------------------------------------------------ safe-dml-plan

	@Nested
	@DisplayName("safe-dml-plan")
	class SafeDmlPlan {

		@Test
		@DisplayName("interpolates the SQL and references preview tools")
		void interpolatesSqlAndReferencesTools() {
			String sql = "UPDATE orders SET status = 'SHIPPED' WHERE order_id = 42";
			String result = prompts.safeDmlPlan(sql);

			assertThat(result).isNotBlank();
			assertThat(result).contains(sql);
			assertThat(result).contains("execute_dml_preview");
			assertThat(result).contains("execute_dml_rollback_first");
			assertThat(result).contains("execute_dml");
		}

		@Test
		@DisplayName("includes pre-flight and execution strategy sections")
		void includesPreFlightAndExecutionSections() {
			String result = prompts.safeDmlPlan("DELETE FROM temp WHERE expiry < SYSDATE");

			assertThat(result).contains("Pre-flight Checks");
			assertThat(result).contains("Execution Strategy");
			assertThat(result).contains("Post-execution");
		}

		@Test
		@DisplayName("warns about missing WHERE clause")
		void warnsAboutMissingWhere() {
			String result = prompts.safeDmlPlan("UPDATE orders SET status = 'X'");

			assertThat(result).contains("WHERE");
			assertThat(result).contains("without WHERE");
		}
	}

	// ------------------------------------------------------------------ data-quality-audit

	@Nested
	@DisplayName("data-quality-audit")
	class DataQualityAudit {

		@Test
		@DisplayName("interpolates schema and table")
		void interpolatesSchemaAndTable() {
			String result = prompts.dataQualityAudit("HR", "EMPLOYEES");

			assertThat(result).isNotBlank();
			assertThat(result).contains("HR");
			assertThat(result).contains("EMPLOYEES");
		}

		@Test
		@DisplayName("references data-quality tools")
		void referencesDataQualityTools() {
			String result = prompts.dataQualityAudit("SALES", "ORDERS");

			assertThat(result).contains("describe_table");
			assertThat(result).contains("count_rows");
			assertThat(result).contains("find_duplicates");
			assertThat(result).contains("validate_fk_integrity");
			assertThat(result).contains("get_fk_graph");
		}

		@Test
		@DisplayName("includes scorecard format")
		void includesScorecardFormat() {
			String result = prompts.dataQualityAudit("SALES", "ORDERS");

			assertThat(result).contains("Completeness");
			assertThat(result).contains("Uniqueness");
			assertThat(result).contains("Referential integrity");
			assertThat(result).contains("Validity");
		}
	}

	// ------------------------------------------------------------------ oracle-sql-style

	@Nested
	@DisplayName("oracle-sql-style")
	class OracleSqlStyle {

		@Test
		@DisplayName("returns the bundled authoring rules")
		void returnsBundledRules() {
			String result = prompts.oracleSqlStyle();

			assertThat(result).isNotBlank();
			assertThat(result).contains("Oracle SQL authoring rules");
			assertThat(result).contains("ORA-00923");
			assertThat(result).contains("FETCH FIRST");
		}
	}
}
