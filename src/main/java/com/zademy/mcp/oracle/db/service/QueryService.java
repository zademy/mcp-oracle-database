package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.QueryResult;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs user-supplied SELECT statements and returns sample data from a table.
 * Safety is enforced by the least-privilege Oracle user, which has per-object
 * SELECT grants but no DDL/DCL privileges; disallowed statements are rejected
 * by Oracle. This service executes the statement verbatim.
 */
@Service
public class QueryService {

	private final OracleDataAccess db;
	private final OracleMcpProperties props;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db     the data access layer that executes the SELECT
	 * @param props  the {@code oracle.mcp.*} properties (used for the default
	 *               sample row count)
	 */
	public QueryService(OracleDataAccess db, OracleMcpProperties props) {
		this.db = db;
		this.props = props;
	}

	/**
	 * Validates and executes a single SELECT statement with no pagination
	 * (offset 0, the server-configured row limit). Convenience overload.
	 *
	 * @param sql the AI-provided SQL (must be a single SELECT)
	 * @return a {@link QueryResult}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public QueryResult runQuery(String sql) {
		return runQuery(sql, null, null);
	}

	/**
	 * Validates and executes a single SELECT statement with optional stateless
	 * offset/limit pagination. When {@code offset} is 0 and {@code limit} equals
	 * the server row cap (the common, non-paginated case) the statement is run
	 * verbatim; otherwise it is wrapped as
	 * {@code SELECT * FROM (<sql>) OFFSET n ROWS FETCH NEXT m ROWS ONLY}. The
	 * limit is always capped at {@link OracleDataAccess#maxRows()}.
	 *
	 * <p>Pagination is non-deterministic unless the supplied SQL contains an
	 * {@code ORDER BY}; callers that need stable pages should include one.
	 *
	 * @param sql    the AI-provided SQL (must be a single SELECT)
	 * @param offset 0-based row offset; {@code null}/{@code <0} means 0
	 * @param limit  maximum rows to return; {@code null}/{@code <=0} means the server cap
	 * @return a {@link QueryResult} carrying columns, the page of rows, count,
	 *         truncation flag, the global row limit, and the applied offset/limit
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public QueryResult runQuery(String sql, Integer offset, Integer limit) {
		int maxRows = db.maxRows();
		int off = (offset == null || offset < 0) ? 0 : offset;
		int lim = (limit == null || limit <= 0) ? maxRows : Math.min(limit, maxRows);
		boolean paginate = off > 0 || lim != maxRows;

		String effective = paginate
				? "SELECT * FROM (" + sql + ") OFFSET " + off + " ROWS FETCH NEXT " + lim + " ROWS ONLY"
				: sql;

		List<Map<String, Object>> rows = db.queryForList(effective);
		return toResult(rows, off, lim);
	}

	/**
	 * Return the first {@code rows} rows of a table. Identifiers are validated and
	 * double-quoted; the row count is a server-controlled integer, so no injection
	 * surface exists.
	 *
	 * @param schema owner of the table; validated by {@link SqlIdentifiers#qualified}
	 * @param table  table name; validated by {@link SqlIdentifiers#qualified}
	 * @param rows   desired number of rows; if {@code <= 0} the configured
	 *               default ({@link OracleMcpProperties#defaultSampleRows()}) is used
	 * @return a {@link QueryResult} for the sample
	 * @throws IllegalArgumentException           if {@code schema}/{@code table}
	 *         fail the identifier allow-list
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public QueryResult sampleData(String schema, String table, int rows) {
		int limit = rows > 0 ? rows : props.defaultSampleRows();
		String sql = "SELECT * FROM " + SqlIdentifiers.qualified(schema, table)
				+ " FETCH FIRST " + limit + " ROWS ONLY";
		List<Map<String, Object>> data = db.queryForList(sql);
		return toResult(data, 0, limit);
	}

	/**
	 * Normalises the raw row list into a {@link QueryResult}, deriving the column
	 * list from the first row. The {@code truncated} flag is true when the page is
	 * full (its size equals the applied limit), hinting that more rows likely exist.
	 *
	 * @param rows raw rows from the database
	 * @param off  the offset that was applied
	 * @param lim  the limit that was applied
	 * @return a populated {@link QueryResult}
	 */
	private QueryResult toResult(List<Map<String, Object>> rows, int off, int lim) {
		List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
		return new QueryResult(columns, rows, rows.size(), rows.size() == lim, db.maxRows(), off, lim);
	}
}
