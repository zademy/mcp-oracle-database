package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.ExplainPlanService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * MCP tool that produces an Oracle execution plan for a SQL statement without
 * running it.
 */
@Component
public class ExplainPlanTools {

	private final ExplainPlanService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the explain-plan service that populates and reads {@code PLAN_TABLE}
	 */
	public ExplainPlanTools(ExplainPlanService service) {
		this.service = service;
	}

	/**
	 * Generates and returns the Oracle execution plan for a SQL statement via
	 * {@code EXPLAIN PLAN} and {@code DBMS_XPLAN}, without executing the statement.
	 *
	 * @param sql the SQL statement to plan (typically SELECT, INSERT, UPDATE, DELETE or MERGE)
	 * @return the formatted execution plan as produced by {@code DBMS_XPLAN.DISPLAY}
	 */
	@McpTool(name = "explain_plan",
			description = "Generate and return the Oracle execution plan for a SQL statement via EXPLAIN PLAN and DBMS_XPLAN, without executing the statement. Needs a PLAN_TABLE; if it is missing or the user lacks access, the Oracle error is returned as a string.")
	public String explainPlan(
			@McpToolParam(description = "The SQL statement to plan (typically SELECT, INSERT, UPDATE, DELETE or MERGE).", required = true) String sql) {
		try {
			return service.explain(sql);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (DataAccessException ex) {
			return SqlErrorHints.enrich(ex.getMessage());
		}
	}
}
