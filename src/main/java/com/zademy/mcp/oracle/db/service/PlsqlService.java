package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.model.PlsqlCallArg;
import com.zademy.mcp.oracle.db.model.PlsqlCallResult;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import oracle.jdbc.OracleTypes;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.stereotype.Service;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access to PL/SQL objects: read-only introspection ({@link #describe}) and
 * controlled execution ({@link #call}) of procedures, functions and package
 * subprograms.
 */
@Service
public class PlsqlService {

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer (named-parameter path for introspection,
	 *           callable path for execution)
	 */
	public PlsqlService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Describes a PL/SQL object (procedure, function, package, type body).
	 *
	 * <p>Queries {@code ALL_ARGUMENTS} for the argument list and
	 * {@code ALL_SOURCE} for the source text. When {@code type} is {@code null}
	 * or blank, all object types matching the name are returned; otherwise the
	 * source is filtered to that single type.
	 *
	 * @param schema owner of the object (validated/quoted as a bind parameter)
	 * @param name   object name (validated/quoted as a bind parameter)
	 * @param type   object type filter (e.g. {@code "PROCEDURE"},
	 *               {@code "FUNCTION"}, {@code "PACKAGE BODY"}); pass
	 *               {@code null} to disable the filter
	 * @return a map with keys {@code schema}, {@code name}, {@code type},
	 *         {@code arguments} (list of rows) and {@code source} (full text)
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public Map<String, Object> describe(String schema, String name, String type) {
		List<Map<String, Object>> arguments = db.queryForList("""
				SELECT argument_name, data_type, in_out, position, defaulted
				FROM all_arguments
				WHERE owner = :schema AND object_name = :name
				ORDER BY position
				""", Map.of("schema", schema, "name", name));

		List<Map<String, Object>> sourceLines = db.queryForList("""
				SELECT line, text
				FROM all_source
				WHERE owner = :schema AND name = :name AND (:type IS NULL OR type = :type)
				ORDER BY line
				""", BindParams.of("schema", schema, "name", name, "type", type));

		String source = sourceLines.stream()
				.map(row -> String.valueOf(row.get("text")))
				.reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
				.toString();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schema", schema);
		result.put("name", name);
		result.put("type", type);
		result.put("arguments", arguments);
		result.put("source", source);
		return result;
	}

	/**
	 * Executes a PL/SQL procedure, function or package subprogram via a JDBC
	 * callable statement. The call string is assembled internally from validated
	 * identifiers and typed bind positions; it never carries client SQL.
	 *
	 * <p>Supported on Oracle 19c: scalar IN/OUT/INOUT parameters, function return
	 * values and a single {@code SYS_REFCURSOR} OUT/return argument (fetched into
	 * {@code cursorResult}, capped by {@code oracle.mcp.max-rows}). PL/SQL
	 * {@code BOOLEAN}, {@code RECORD}, {@code TABLE}, {@code VARRAY} and
	 * {@code OBJECT} types are not bindable via JDBC and are rejected up front
	 * with guidance to create a scalar wrapper.
	 *
	 * @param schema      owner of the object
	 * @param name        object name, optionally qualified as {@code PKG.PROC}
	 * @param args        argument list (each with name, direction, dataType, value)
	 * @param isFunction  {@code true} if the object is a function (a return slot
	 *                    is registered and {@code returnValue} is populated)
	 * @param returnType  Oracle return type for functions (ignored when
	 *                    {@code isFunction} is {@code false}); may be {@code null}
	 *                    for a parameterless function
	 * @return the OUT values, return value and (optional) cursor rows
	 * @throws IllegalArgumentException if any argument or the return type is not
	 *         bindable via JDBC on Oracle 19c, or if {@code schema}/{@code name}
	 *         contain invalid identifiers
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public PlsqlCallResult call(String schema, String name, List<PlsqlCallArg> args, boolean isFunction, String returnType) {
		List<PlsqlCallArg> callArgs = args == null ? List.of() : args;
		if (isFunction && returnType != null && !returnType.isBlank()) {
			requireSupportedType(returnType, "return type");
		}
		for (PlsqlCallArg a : callArgs) {
			requireSupportedType(a.dataType(), "argument '" + a.name() + "'");
		}

		String callSql = buildCallSql(schema, name, callArgs.size(), isFunction);
		int maxRows = db.maxRows() > 0 ? db.maxRows() : 1000;
		return db.call(callSql, new PlsqlCallExecutor(callArgs, isFunction, returnType, maxRows));
	}

	// ---- pure helpers (testable without a database) --------------------------

	static List<String> parseQualifiedName(String name) {
		String[] parts = name.split("\\.");
		List<String> validated = new ArrayList<>(parts.length);
		for (String p : parts) {
			validated.add(SqlIdentifiers.validate(p));
		}
		return validated;
	}

	static String buildCallSql(String schema, String name, int argCount, boolean isFunction) {
		StringBuilder qualified = new StringBuilder(SqlIdentifiers.quote(schema));
		for (String p : parseQualifiedName(name)) {
			qualified.append('.').append(SqlIdentifiers.quote(p));
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < argCount; i++) {
			if (i > 0) {
				placeholders.append(", ");
			}
			placeholders.append("?");
		}
		String body = qualified + "(" + placeholders + ")";
		return isFunction ? "{ ? = call " + body + " }" : "{ call " + body + " }";
	}

	static int jdbcType(String oracleType) {
		String t = oracleType == null ? "" : oracleType.trim().toUpperCase();
		if (t.equals("REF CURSOR") || t.equals("CURSOR")) {
			return OracleTypes.CURSOR;
		}
		if (t.equals("BLOB") || t.equals("RAW") || t.equals("LONG RAW") || t.equals("BFILE")) {
			return Types.BLOB;
		}
		if (t.equals("CLOB") || t.equals("NCLOB") || t.equals("LONG")) {
			return Types.CLOB;
		}
		if (t.startsWith("TIMESTAMP")) {
			return Types.TIMESTAMP;
		}
		if (t.equals("DATE")) {
			return Types.DATE;
		}
		if (t.equals("NUMBER") || t.equals("NUMERIC") || t.equals("DECIMAL") || t.equals("INTEGER")
				|| t.equals("INT") || t.equals("SMALLINT") || t.equals("FLOAT")
				|| t.equals("BINARY_FLOAT") || t.equals("BINARY_DOUBLE")
				|| t.equals("BINARY_INTEGER") || t.equals("PLS_INTEGER")
				|| t.equals("NATURAL") || t.equals("POSITIVE")) {
			return Types.NUMERIC;
		}
		return Types.VARCHAR;
	}

	static void requireSupportedType(String oracleType, String label) {
		String t = oracleType == null ? "" : oracleType.trim().toUpperCase();
		if (t.isEmpty() || t.equals("REF CURSOR") || t.equals("CURSOR")) {
			return;
		}
		boolean composite = t.equals("BOOLEAN") || t.equals("RECORD") || t.equals("TABLE")
				|| t.equals("VARRAY") || t.equals("OBJECT")
				|| t.startsWith("PL/SQL") || t.startsWith("TABLE OF")
				|| (t.startsWith("REF ") && !t.equals("REF CURSOR"));
		if (composite) {
			throw new IllegalArgumentException(
					"PL/SQL " + label + " of type '" + oracleType + "' is not bindable via JDBC on Oracle 19c. "
							+ "Create a wrapper subprogram that exposes scalar SQL types instead "
							+ "(for example a NUMBER-to-BOOLEAN wrapper: "
							+ "PROCEDURE wrap(n NUMBER) IS BEGIN proc(n <> 0); END;).");
		}
	}

	private record PlsqlCallExecutor(
			List<PlsqlCallArg> args,
			boolean isFunction,
			String returnType,
			int maxRows
	) implements CallableStatementCallback<PlsqlCallResult> {

		@Override
		public PlsqlCallResult doInCallableStatement(CallableStatement cs) throws SQLException {
			int pos = 1;
			if (isFunction) {
				cs.registerOutParameter(pos, jdbcType(returnType));
				pos++;
			}
			for (PlsqlCallArg a : args) {
				String dir = direction(a);
				int code = jdbcType(a.dataType());
				if (isOut(dir)) {
					cs.registerOutParameter(pos, code);
				}
				if (isIn(dir)) {
					bindIn(cs, pos, code, a.value());
				}
				pos++;
			}
		cs.setFetchSize(100);
		cs.execute();

		Map<String, Object> outParams = new LinkedHashMap<>();
			ResultSet cursor = null;
			pos = isFunction ? 2 : 1;
			for (PlsqlCallArg a : args) {
				String dir = direction(a);
				if (isOut(dir)) {
					Object out = cs.getObject(pos);
					if (jdbcType(a.dataType()) == OracleTypes.CURSOR && out instanceof ResultSet rs && cursor == null) {
						cursor = rs;
					}
					if (a.name() != null && !a.name().isBlank()) {
						outParams.put(a.name(), out);
					}
				}
				pos++;
			}
			Object returnValue = null;
			if (isFunction) {
				returnValue = cs.getObject(1);
				if (jdbcType(returnType) == OracleTypes.CURSOR && returnValue instanceof ResultSet rs && cursor == null) {
					cursor = rs;
				}
			}
			List<Map<String, Object>> cursorResult = null;
			if (cursor != null) {
				try (ResultSet c = cursor) {
					cursorResult = fetchCursor(c, maxRows);
				}
			}
			return new PlsqlCallResult(outParams, returnValue, cursorResult);
		}

		private static String direction(PlsqlCallArg a) {
			return a.direction() == null ? "IN" : a.direction().trim().toUpperCase();
		}

		private static boolean isIn(String dir) {
			return dir.equals("IN") || dir.equals("INOUT") || dir.equals("IN OUT");
		}

		private static boolean isOut(String dir) {
			return dir.equals("OUT") || dir.equals("INOUT") || dir.equals("IN OUT");
		}

		private static void bindIn(CallableStatement cs, int pos, int code, Object value) throws SQLException {
			if (value == null) {
				cs.setNull(pos, code);
			} else {
				cs.setObject(pos, value, code);
			}
		}

		private static List<Map<String, Object>> fetchCursor(ResultSet rs, int maxRows) throws SQLException {
			int columnCount = rs.getMetaData().getColumnCount();
			List<String> labels = new ArrayList<>(columnCount);
			for (int c = 1; c <= columnCount; c++) {
				labels.add(rs.getMetaData().getColumnLabel(c));
			}
			List<Map<String, Object>> rows = new ArrayList<>();
			int count = 0;
			while (rs.next() && (maxRows <= 0 || count < maxRows)) {
				Map<String, Object> row = new LinkedHashMap<>(columnCount);
				for (int c = 1; c <= columnCount; c++) {
					row.put(labels.get(c - 1), rs.getObject(c));
				}
				rows.add(row);
				count++;
			}
			return rows;
		}
	}
}
