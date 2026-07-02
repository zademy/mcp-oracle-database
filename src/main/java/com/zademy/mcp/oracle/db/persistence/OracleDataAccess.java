package com.zademy.mcp.oracle.db.persistence;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Single entry point for all database access.
 * <p>
 * Two clearly separated paths:
 * <ul>
 *   <li><b>Raw SQL</b> ({@link #queryForList(String)}, {@link #update(String)},
 *       {@link #execute(String)}) — used for AI-provided SQL that must not be
 *       re-parsed for named parameters (literals may contain ':').</li>
 *   <li><b>Named-parameter</b> ({@link #queryForList(String, Map)},
 *       {@link #queryForObject(String, Map, Class)}) — used by internal metadata
 *       queries with {@code :param} placeholders.</li>
 * </ul>
 *
 * <p>Every method delegates to Spring's {@link JdbcTemplate} or
 * {@link NamedParameterJdbcTemplate}, both of which inherit the global
 * query-timeout and max-rows caps configured in {@link JdbcConfig}.
 */
@Component
public class OracleDataAccess {

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate namedJdbc;
	private final OracleMcpProperties props;

	/**
	 * Spring-injected constructor.
	 *
	 * @param jdbc      the primary template (with timeout + max-rows caps)
	 * @param namedJdbc the named-parameter wrapper around {@code jdbc}
	 * @param props     the {@code oracle.mcp.*} properties, used for runtime caps
	 */
	public OracleDataAccess(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, OracleMcpProperties props) {
		this.jdbc = jdbc;
		this.namedJdbc = namedJdbc;
		this.props = props;
	}

	/**
	 * Returns the maximum number of rows a SELECT may return.
	 *
	 * @return the global row cap from {@link OracleMcpProperties#maxRows()}
	 */
	public int maxRows() {
		return props.maxRows();
	}

	// ---- raw SQL (AI-provided) -------------------------------------------------

	/**
	 * Runs a raw SELECT and returns every row as a label-to-value map.
	 *
	 * <p>The result size is bounded by {@link #maxRows()}; callers should check
	 * {@code result.size() == maxRows()} to detect truncation.
	 *
	 * @param sql the AI-provided SQL
	 * @return an ordered list of rows; never {@code null}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public List<Map<String, Object>> queryForList(String sql) {
		return jdbc.queryForList(sql);
	}

	/**
	 * Runs a raw DML statement (INSERT/UPDATE/DELETE/MERGE).
	 *
	 * @param sql the AI-provided SQL
	 * @return the number of rows affected in the database
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public int update(String sql) {
		return jdbc.update(sql);
	}

	/**
	 * Executes a raw statement that returns no rows (e.g. {@code EXPLAIN PLAN},
	 * DDL handled by the safe-subset path).
	 *
	 * @param sql the AI-provided SQL
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public void execute(String sql) {
		jdbc.execute(sql);
	}

	// ---- callable (PL/SQL) -----------------------------------------------------

	/**
	 * Executes a PL/SQL callable statement (procedure, function or package
	 * subprogram). Unlike the raw-SQL path, {@code callSql} is assembled
	 * internally from validated identifiers and typed bind positions &mdash; it
	 * never contains client SQL &mdash; so it carries no client-authored SQL
	 * text. The callback is responsible for registering OUT parameters, binding
	 * IN values, executing and collecting results.
	 *
	 * <p>The underlying {@link JdbcTemplate} still applies the global query-timeout
	 * cap to the callable statement. A {@code SYS_REFCURSOR} returned via
	 * {@code getObject} is fetched separately by the callback, which must cap the
	 * row count itself (typically via {@link #maxRows()}).
	 *
	 * @param callSql a JDBC call escape of the form
	 *                {@code { ? = call "s"."p"(?, ?) }} or {@code { call "s"."p"(?, ?) }}
	 * @param action  the callback that binds and executes the statement
	 * @param <T>     the result type produced by the callback
	 * @return whatever the callback returns
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public <T> T call(String callSql, CallableStatementCallback<T> action) {
		return jdbc.execute(callSql, action);
	}

	// ---- named-parameter (internal metadata) -----------------------------------

	/**
	 * Runs a parameterised query with named {@code :param} placeholders. Used by
	 * the internal metadata path (e.g. {@code ALL_TABLES}, {@code ALL_SOURCE}).
	 *
	 * @param sql    a SQL string with {@code :name} placeholders
	 * @param params bind values keyed by placeholder name; {@code null} is
	 *               treated as an empty map
	 * @return an ordered list of rows; never {@code null}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public List<Map<String, Object>> queryForList(String sql, Map<String, Object> params) {
		return namedJdbc.queryForList(sql, params == null ? Map.of() : params);
	}

	/**
	 * Runs a parameterised DML statement with named {@code :param} placeholders.
	 *
	 * @param sql    a SQL string with {@code :name} placeholders
	 * @param params bind values keyed by placeholder name; {@code null} is
	 *               treated as an empty map
	 * @return the number of rows affected in the database
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public int update(String sql, Map<String, Object> params) {
		return namedJdbc.update(sql, params == null ? Map.of() : params);
	}

	/**
	 * Runs a parameterised scalar query expecting exactly one row and one column.
	 *
	 * @param sql    a SQL string with {@code :name} placeholders
	 * @param params bind values keyed by placeholder name; {@code null} is
	 *               treated as an empty map
	 * @param type   the expected Java type of the result
	 * @param <T>    the result type
	 * @return the single scalar value
	 * @throws org.springframework.dao.IncorrectResultSizeDataAccessException if
	 *         the query returns zero or more than one row
	 * @throws org.springframework.dao.DataAccessException on any other Oracle error
	 */
	public <T> T queryForObject(String sql, Map<String, Object> params, Class<T> type) {
		return namedJdbc.queryForObject(sql, params == null ? Map.of() : params, type);
	}

	/**
	 * Runs a parameterless scalar query expecting exactly one row and one column.
	 *
	 * @param sql  a SQL string with no placeholders
	 * @param type the expected Java type of the result
	 * @param <T>  the result type
	 * @return the single scalar value
	 * @throws org.springframework.dao.IncorrectResultSizeDataAccessException if
	 *         the query returns zero or more than one row
	 * @throws org.springframework.dao.DataAccessException on any other Oracle error
	 */
	public <T> T queryForObject(String sql, Class<T> type) {
		return jdbc.queryForObject(sql, type);
	}
}
