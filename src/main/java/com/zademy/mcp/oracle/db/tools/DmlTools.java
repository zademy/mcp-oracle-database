package com.zademy.mcp.oracle.db.tools;

import com.zademy.mcp.oracle.db.service.DmlService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * MCP tools that run DML (INSERT/UPDATE/DELETE/MERGE) on existing tables.
 * Disallowed statements (anything the least-privilege Oracle user cannot run,
 * such as DDL/DCL) come back as an Oracle error string instead of throwing.
 *
 * <p>Three execution modes:
 * <ul>
 *   <li>{@code execute_dml} — apply and commit.</li>
 *   <li>{@code execute_dml_preview} — non-executing dry-run for UPDATE/DELETE.</li>
 *   <li>{@code execute_dml_rollback_first} — execute then roll back, proving the
 *       statement is valid without persisting anything.</li>
 * </ul>
 */
@Component
public class DmlTools {

	private final DmlService service;

	/**
	 * Creates the tool, wiring the backing service.
	 *
	 * @param service the DML service that executes write statements
	 */
	public DmlTools(DmlService service) {
		this.service = service;
	}

	/**
	 * Executes a single INSERT, UPDATE, DELETE or MERGE statement on existing
	 * tables and returns the number of rows affected.
	 *
	 * @param sql a single INSERT, UPDATE, DELETE or MERGE statement
	 * @return a {@code DmlResult} on success, or an Oracle error string on failure
	 */
	@McpTool(name = "execute_dml",
			description = "Execute a single INSERT, UPDATE, DELETE or MERGE statement on existing tables and auto-commit it (no explicit COMMIT needed; there is no cross-call transaction boundary). One statement per call. Returns the number of rows affected; if the Oracle user lacks the required privileges the Oracle error is returned as a string.")
	public Object executeDml(
			@McpToolParam(description = "A single INSERT, UPDATE, DELETE or MERGE statement.", required = true) String sql) {
		try {
			return service.executeDml(sql);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (DataAccessException ex) {
			return SqlErrorHints.enrich(ex.getMessage());
		}
	}

	/**
	 * Non-executing dry-run for a single UPDATE or DELETE. Shows which rows the
	 * statement would affect (sample + total count) by extracting the WHERE
	 * clause and running a SELECT. The statement itself is never executed.
	 * INSERT and MERGE are not supported here.
	 *
	 * @param sql a single UPDATE or DELETE statement
	 * @return a {@code DmlPreviewResult} on success, or an error string on failure
	 */
	@McpTool(name = "execute_dml_preview",
			description = "Dry-run an UPDATE or DELETE without executing it. Returns the total number of rows that would be affected plus a sample of those rows. INSERT and MERGE are not supported; use execute_dml_rollback_first for those.")
	public Object executeDmlPreview(
			@McpToolParam(description = "A single UPDATE or DELETE statement to preview (not execute).", required = true) String sql) {
		try {
			return service.previewDml(sql);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (DataAccessException ex) {
			return SqlErrorHints.enrich(ex.getMessage());
		}
	}

	/**
	 * Executes a single INSERT/UPDATE/DELETE/MERGE inside a transaction that is
	 * immediately rolled back, so nothing is persisted. This proves the
	 * statement is valid and reveals the affected row count. Call
	 * {@code execute_dml} afterwards to apply the change for real.
	 *
	 * @param sql a single INSERT, UPDATE, DELETE or MERGE statement
	 * @return a {@code DmlDryRunResult} on success, or an error string on failure
	 */
	@McpTool(name = "execute_dml_rollback_first",
			description = "Execute an INSERT, UPDATE, DELETE or MERGE inside a transaction that is immediately rolled back. Nothing is persisted; the tool only proves the statement is valid and reports the number of rows affected. Call execute_dml afterwards to apply the change for real.")
	public Object executeDmlRollbackFirst(
			@McpToolParam(description = "A single INSERT, UPDATE, DELETE or MERGE statement to dry-run (executed then rolled back).", required = true) String sql) {
		try {
			return service.rollbackFirstDml(sql);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (DataAccessException ex) {
			return SqlErrorHints.enrich(ex.getMessage());
		}
	}
}
