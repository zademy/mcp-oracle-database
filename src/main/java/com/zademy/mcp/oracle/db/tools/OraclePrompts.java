package com.zademy.mcp.oracle.db.tools;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * MCP Prompts that generate structured, context-rich instructions for the AI
 * client. Each prompt is a parameterised template that references the available
 * MCP tools so the client knows which tools to call and in what order.
 *
 * <p>Prompts return plain {@code String} — Spring AI wraps the text as a user
 * message in the MCP {@code GetPromptResult}.
 */
@Component
public class OraclePrompts {

	private final SqlAuthoringRules sqlAuthoringRules;

	/**
	 * @param sqlAuthoringRules the bundled SQL authoring rules (classpath), used
	 *     by the {@code oracle-sql-style} prompt
	 */
	public OraclePrompts(SqlAuthoringRules sqlAuthoringRules) {
		this.sqlAuthoringRules = sqlAuthoringRules;
	}

	@McpPrompt(
			name = "review-sql-performance",
			description = "Generate a step-by-step performance review plan for a SQL " +
					"statement, including EXPLAIN PLAN analysis and index recommendations."
	)
	public String reviewSqlPerformance(
			@McpArg(name = "sql", description = "The SQL statement to review", required = true)
			String sql) {

		return """
				You are an Oracle SQL performance expert. Analyse the following SQL statement
				and produce a structured performance review.

				## SQL Statement
				```sql
				%s
				```

				## Steps

				1. **Execution plan** — Call `explain_plan` with the SQL above. Examine the
				   plan for:
				   - Full table scans on large tables
				   - Cartesian joins
				   - High-cost operations (TEMP SEGMENT, SORT AGGREGATE)
				   - Missing or unusable indexes

				2. **Index recommendations** — Call `suggest_index` with the same SQL.
				   Evaluate whether the suggested indexes would help. Consider:
				   - Columns in WHERE predicates and JOIN conditions
				   - Foreign-key columns that may need supporting indexes
				   - Composite vs. single-column trade-offs

				3. **Unused-index check** — If the table is known, call `list_unused_indexes`
				   to identify indexes that add overhead without benefit.

				4. **Statistics check** — Call `get_table_stats` for the main table(s).
				   Stale statistics (last_analyzed is null or very old) skew the optimiser.

				5. **SQL Tuning Advisor** — For complex or slow queries, call
				   `run_sql_tuning_advisor` for Oracle's own recommendations.

				## Output Format

				Produce a report with:
				- **Findings** — one bullet per issue found (severity: high/medium/low)
				- **Recommendations** — concrete, actionable fixes (e.g. "create index
				  IX_ORD_CUST on ORDERS(CUSTOMER_ID)")
				- **Rewritten SQL** — if the query can be restructured for better performance,
				  provide the improved version with an explanation of the change
				""".formatted(sql);
	}

	@McpPrompt(
			name = "explain-schema",
			description = "Generate a guided exploration of an Oracle schema, covering " +
					"tables, columns, relationships, and key objects."
	)
	public String explainSchema(
			@McpArg(name = "schema", description = "The Oracle schema (owner) to explore", required = true)
			String schema,
			@McpArg(name = "table", description = "Optional: focus on a specific table", required = false)
			String table) {

		if (table != null && !table.isBlank()) {
			return """
					You are an Oracle data architect. Provide a thorough explanation of the
					table **%s.%s**.

					## Steps

					1. **Structure** — Call `describe_table` for `%s`, `%s`. Summarise each
					   column: name, data type, nullable, default, and comment.

					2. **DDL** — Call `get_ddl` for `%s`, `%s` to see the full definition
					   including constraints, storage, and partitioning.

					3. **Indexes** — Call `list_indexes` for `%s`, `%s`. Explain which
					   queries each index supports.

					4. **Constraints** — Call `list_constraints` for `%s`, `%s`. Identify
					   primary key, unique constraints, check constraints, and foreign keys.

					5. **Foreign-key graph** — Call `get_fk_graph` for `%s`, `%s`. Explain
					   how this table relates to its parent and child tables.

					6. **Statistics** — Call `get_table_stats` and `count_rows` to estimate
					   table size and data distribution.

					## Output Format

					Produce a concise data dictionary entry:
					- **Purpose** — what business entity the table represents
					- **Columns** — markdown table (name, type, nullable, description)
					- **Relationships** — FK parents and children, with cardinality notes
					- **Indexes** — which access patterns each index supports
					- **Data volume** — estimated row count and last-analyzed date
					""".formatted(schema, table, schema, table, schema, table,
					schema, table, schema, table, schema, table);
		}

		return """
				You are an Oracle data architect. Provide a high-level overview of the
				schema **%s**.

				## Steps

				1. **Table inventory** — Call `list_tables` for schema `%s`. Group tables
				   by likely business domain (e.g. "orders", "customers", "audit").

				2. **Key objects** — Call `list_views`, `list_sequences`, `list_triggers`,
				   and `list_procedures` to map the full schema footprint.

				3. **Relationships** — Call `get_fk_graph` for schema `%s` (no specific
				   table) to visualise the foreign-key topology.

				4. **Sample DDL** — Pick 3-5 central tables and call `get_ddl` for each.
				   Identify the most important entities and their constraints.

				5. **Object counts** — Call `count_rows` on the main tables to estimate
				   data volume.

				## Output Format

				Produce a schema overview:
				- **Summary** — total tables, views, sequences, procedures
				- **Entity groups** — tables grouped by domain with a one-line description
				- **Key relationships** — the most important FK paths
				- **Notable patterns** — partitioning, materialized views, triggers
				""".formatted(schema, schema, schema);
	}

	@McpPrompt(
			name = "debug-invalid-plsql",
			description = "Generate a debugging workflow for invalid PL/SQL objects in a " +
					"schema, including error extraction and source-code analysis."
	)
	public String debugInvalidPlsql(
			@McpArg(name = "schema", description = "The Oracle schema to check", required = true)
			String schema,
			@McpArg(name = "object", description = "Optional: focus on a specific PL/SQL object name", required = false)
			String object) {

		String focusClause = (object != null && !object.isBlank())
				? "Focus specifically on the object **%s.%s**.".formatted(schema, object)
				: "Identify all invalid objects in the schema.";

		return """
				You are an Oracle PL/SQL debugging expert. %s

				## Steps

				1. **Find invalid objects** — Call `list_invalid_objects` for schema `%s`.
				   Note the object name, type, and when it was last modified.

				2. **Get compilation errors** — For each invalid object, call
				   `get_plsql_errors` with its schema, name, and type. The errors include
				   line numbers and error messages (PLS-XXXX, ORA-XXXX).

				3. **Retrieve source code** — Call `describe_plsql` for each invalid
				   object. Cross-reference the error line numbers with the source.

				4. **Check dependencies** — Call `list_dependencies` for each object. An
				   invalid state may be caused by a changed dependency (e.g. an altered
				   table column referenced in the PL/SQL).

				5. **Formulate fixes** — For each error, propose:
				   - The exact line to change
				   - The corrected code
				   - The reason for the error

				## Common PL/SQL Errors to Watch For

				- **PLS-00103** — syntax error (missing semicolon, mismatched quotes)
				- **PLS-00201** — identifier not declared (typo, missing grant, missing synonym)
				- **PLS-00302** — component must be declared (wrong package member)
				- **PLS-00382** — expression is of wrong type
				- **ORA-06550** — generic compilation error with line/col

				## Output Format

				For each invalid object:
				- **Object** — schema.name (type)
				- **Errors** — table of line, error code, message
				- **Root cause** — one-sentence explanation
				- **Fix** — the corrected source snippet (only the changed portion)
				""".formatted(focusClause, schema);
	}

	@McpPrompt(
			name = "safe-dml-plan",
			description = "Generate a safe execution plan for a DML statement (INSERT, " +
					"UPDATE, DELETE, MERGE), including preview, impact assessment, and " +
					"rollback strategy."
	)
	public String safeDmlPlan(
			@McpArg(name = "sql", description = "The DML statement to plan", required = true)
			String sql) {

		return """
				You are a database operations specialist. Create a safe execution plan for
				the following DML statement.

				## DML Statement
				```sql
				%s
				```

				## Pre-flight Checks

				1. **Validate the statement** — Ensure it is a single DML statement
				   (INSERT, UPDATE, DELETE, or MERGE). If it is DDL or DCL, stop: this
				   server blocks structural changes.

				2. **Preview the impact** — Call `execute_dml_preview` with the SQL. This
				   returns the affected row count and a sample of the rows that will be
				   changed — **without executing** the change. Examine:
				   - Is the affected row count reasonable? (A 0-row UPDATE may indicate a
				     bad WHERE clause; a million-row DELETE is suspicious.)
				   - Do the sample rows look correct? (Right columns, expected values.)

				3. **For UPDATE/DELETE** — Verify the WHERE clause is present and selective.
				   A DML without WHERE affects every row.

				## Execution Strategy

				4. **Dry-run first** — Call `execute_dml_rollback_first` with the SQL. This
				   executes the statement, reports the affected row count, then
				   **immediately rolls back**. Confirm the count matches the preview.

				5. **Commit** — If the dry-run count is correct, call `execute_dml` to
				   commit the change permanently.

				## Post-execution

				6. **Verify** — Run a `run_query` to confirm the data changed as expected
				   (e.g. a SELECT showing the updated rows).

				## Output Format

				- **Risk assessment** — low/medium/high, with reasoning
				- **Preview summary** — affected row count + sample highlights
				- **Execution decision** — proceed / adjust / abort
				- **Verification query** — a SELECT to confirm the result
				""".formatted(sql);
	}

	@McpPrompt(
			name = "data-quality-audit",
			description = "Generate a comprehensive data-quality audit plan for a specific " +
					"table, covering nulls, duplicates, referential integrity, and value " +
					"distribution."
	)
	public String dataQualityAudit(
			@McpArg(name = "schema", description = "The Oracle schema name", required = true)
			String schema,
			@McpArg(name = "table", description = "The table to audit", required = true)
			String table) {

		return """
				You are a data-quality analyst. Perform a comprehensive audit of the table
				**%s.%s**.

				## Steps

				1. **Table profile** — Call `describe_table` for `%s`, `%s`. Identify:
				   - Primary key column(s)
				   - NOT NULL columns
				   - Columns with CHECK constraints
				   - Foreign-key columns

				2. **Row count** — Call `count_rows` for `%s`, `%s`. Establish the baseline.

				3. **Null analysis** — For each nullable column, run a `run_query` counting
				   nulls:
				   ```sql
				   SELECT COUNT(*) AS null_count,
				          ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM "%s"."%s"), 2) AS pct_nulls
				   FROM "%s"."%s"
				   WHERE <column> IS NULL
				   ```
				   Flag columns with >10%% nulls.

				4. **Duplicate detection** — Call `find_duplicates` for the primary key
				   column(s). Also check natural-key columns if they differ from the PK.

				5. **Referential integrity** — Call `validate_fk_integrity` for the table.
				   Orphaned child rows indicate broken relationships.

				6. **Value distribution** — For key categorical columns (low cardinality),
				   run a `run_query` with GROUP BY to check for unexpected values:
				   ```sql
				   SELECT <column>, COUNT(*) AS cnt
				   FROM "%s"."%s"
				   GROUP BY <column>
				   ORDER BY cnt DESC
				   FETCH FIRST 20 ROWS ONLY
				   ```

				7. **Foreign-key graph** — Call `get_fk_graph` for `%s`, `%s` to understand
				   which downstream tables are affected by data quality in this table.

				## Output Format

				Produce a data-quality scorecard:

				| Dimension | Status | Detail |
				|---|---|---|
				| Completeness | ✅/⚠️/❌ | null%% per critical column |
				| Uniqueness | ✅/⚠️/❌ | duplicate count on PK |
				| Referential integrity | ✅/⚠️/❌ | orphan count |
				| Validity | ✅/⚠️/❌ | unexpected values found |
				| Consistency | ✅/⚠️/❌ | cross-column issues |

				End with a **priority list** of recommended fixes.
				""".formatted(schema, table, schema, table, schema, table,
				schema, table, schema, table, schema, table, schema, table);
	}

	@McpPrompt(
			name = "operating-rules",
			description = "The server-side operating rules for this Oracle MCP server: " +
					"calling discipline, scope boundaries, and safety constraints the " +
					"client must follow. Pull this before non-trivial work to align with " +
					"the server's embedded policy."
	)
	public String operatingRules() {
		return """
				These are the operating rules for the mcp-oracle-db server. They are
				shipped inside the server and cannot be modified by the client. Follow
				them in every session.

				## 1. Calling discipline — do not run preamble queries

				Every tool call consumes one of a very small number of database
				connections (the pool is capped at 5 by default). Wasteful calls hurt
				everyone.

				- Do **not** call `test_connection`, `oracle_mcp_health_report`,
				  `get_session_privs`, or `get_session_roles` as a preamble before
				  answering a request. Call them **only** when the user explicitly asks
				  about connectivity, health, privileges, or roles.
				- If the user asks about a table, go straight to `describe_table`,
				  `get_ddl`, `list_indexes`, or `run_query` as appropriate. Do not
				  "orient" first by listing schemas, roles, or IP/identity.
				- Never call a tool to learn the current user's ADMIN status, IP,
				  hostname, or terminal unless the user explicitly asks for it.

				## 2. Stay in scope

				Answer exactly what is asked. Do not expand scope by gathering
				privileges, session identity, or catalogue metadata the user did not
				request. If a request is ambiguous, ask one clarifying question rather
				than probing.

				## 3. One statement per call

				Accept a single SQL statement per `run_query` / `execute_dml` call.
				Reject multi-statement input. Oracle already rejects multi-statement
				input, so do not try to work around it.

				## 4. Read-only bias; explain before writes

				Prefer read-only actions. Before any `INSERT`/`UPDATE`/`DELETE`/`MERGE`,
				state the risk and use `execute_dml_preview` to show affected rows.
				`DELETE` is row deletion, never object deletion.

				## 5. Never attempt structural changes

				Do not recommend or attempt `ALTER`, `DROP`, `TRUNCATE`, `RENAME`,
				`GRANT`, `REVOKE`, `PURGE`, `FLASHBACK`, `AUDIT`, `NOAUDIT`,
				`LOCK TABLE`, `ANALYZE`, or any `CREATE ...` form. If an action is
				blocked by least-privilege grants, return the reason
				and suggest the safest read-only alternative.

				## 6. Result hygiene

				Results are capped by server-side `max-rows` and `query-timeout`. If a
				result looks truncated, say so and offer pagination — do not silently
				rerun with broader scope.
				""";
	}

	@McpPrompt(
			name = "oracle-sql-style",
			description = "Return the server's canonical Oracle SQL authoring rules " +
					"(identifiers, aliases, literals, pagination, dialect pitfalls) " +
					"that prevent common parse errors such as ORA-00923 and ORA-00936."
	)
	public String oracleSqlStyle() {
		return sqlAuthoringRules.fullRules();
	}
}
