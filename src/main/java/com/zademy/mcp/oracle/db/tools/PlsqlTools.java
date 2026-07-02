package com.zademy.mcp.oracle.db.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zademy.mcp.oracle.db.model.PlsqlCallArg;
import com.zademy.mcp.oracle.db.service.PlsqlService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tools for inspecting and executing PL/SQL objects (procedures, functions,
 * packages).
 */
@Component
public class PlsqlTools {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final PlsqlService service;

	/**
	 * Creates the tool, wiring the backing service. A dedicated
	 * {@link ObjectMapper} (thread-safe once configured) parses the
	 * {@code call_procedure} argument list; a shared Spring bean is not required
	 * for this self-contained use case.
	 *
	 * @param service the PL/SQL service (describe + call)
	 */
	public PlsqlTools(PlsqlService service) {
		this.service = service;
	}

	/**
	 * Describes a PL/SQL object: returns its arguments (name, data type, in/out,
	 * position) and full source code.
	 *
	 * @param schema schema (owner) of the object
	 * @param name   object name (procedure, function or package)
	 * @param type   object type, e.g. {@code "PROCEDURE"}, {@code "FUNCTION"}, {@code "PACKAGE"} or {@code "PACKAGE BODY"}; may be {@code null}
	 * @return a map with {@code arguments} and {@code source} entries
	 */
	@McpTool(name = "describe_plsql",
			description = "Describe a PL/SQL object: returns its arguments (name, data type, in/out, position) and full source code. Read-only.")
	public Map<String, Object> describePlsql(
			@McpToolParam(description = "Schema (owner) of the object.", required = true) String schema,
			@McpToolParam(description = "Object name (procedure, function or package).", required = true) String name,
			@McpToolParam(description = "Object type, e.g. 'PROCEDURE', 'FUNCTION', 'PACKAGE' or 'PACKAGE BODY'.", required = false) String type) {
		return service.describe(schema, name, type);
	}

	/**
	 * Executes a PL/SQL procedure, function or package subprogram with typed
	 * arguments. Supports IN/OUT/INOUT scalar parameters, function return values
	 * and a single {@code SYS_REFCURSOR} OUT/return argument. PL/SQL
	 * {@code BOOLEAN}, {@code RECORD}, {@code TABLE}, {@code VARRAY} and
	 * {@code OBJECT} types are not bindable via JDBC on Oracle 19c and are
	 * rejected with guidance to create a scalar wrapper.
	 *
	 * @param schema      schema (owner) of the object
	 * @param name        object name, optionally qualified as {@code PKG.PROC}
	 * @param args        JSON array of arguments, e.g.
	 *                    {@code [{"name":"P","direction":"IN","dataType":"NUMBER","value":1}]}
	 * @param isFunction  {@code true} if the object is a function
	 * @param returnType  Oracle return type for functions (e.g. {@code NUMBER},
	 *                    {@code VARCHAR2}, {@code REF CURSOR}); ignored for procedures
	 * @return a {@code PlsqlCallResult} on success, or an explanatory message on error
	 */
	@McpTool(name = "call_procedure",
			description = "Execute a PL/SQL procedure, function or package subprogram with typed arguments via a JDBC callable statement. Supports IN/OUT/INOUT scalar parameters, function return values and a SYS_REFCURSOR OUT/return argument. Pass args as a JSON array: [{\"name\":\"P\",\"direction\":\"IN\",\"dataType\":\"NUMBER\",\"value\":1}]. PL/SQL BOOLEAN/RECORD/TABLE/VARRAY/OBJECT types are rejected with guidance to create a scalar wrapper. Requires GRANT EXECUTE on the target object.")
	public Object callProcedure(
			@McpToolParam(description = "Schema (owner) of the object.", required = true) String schema,
			@McpToolParam(description = "Object name; optionally qualified as PKG.PROC for a package subprogram.", required = true) String name,
			@McpToolParam(description = "Arguments as a JSON array: [{\"name\":\"P\",\"direction\":\"IN|OUT|INOUT\",\"dataType\":\"NUMBER\",\"value\":1}]. Use \"dataType\":\"REF CURSOR\" for a SYS_REFCURSOR OUT parameter.", required = true) String args,
			@McpToolParam(description = "Set true if the object is a function (registers a return slot and populates returnValue).", required = false) Boolean isFunction,
			@McpToolParam(description = "Oracle return type for functions (e.g. NUMBER, VARCHAR2, REF CURSOR). Ignored for procedures.", required = false) String returnType) {
		try {
			List<PlsqlCallArg> parsed = OBJECT_MAPPER.readValue(args, new TypeReference<List<PlsqlCallArg>>() {
			});
			boolean fn = isFunction != null && isFunction;
			return service.call(schema, name, parsed, fn, returnType);
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		} catch (JsonProcessingException ex) {
			return "Invalid args JSON: " + ex.getOriginalMessage();
		} catch (DataAccessException ex) {
			return "Oracle error: " + ex.getMessage();
		}
	}
}
