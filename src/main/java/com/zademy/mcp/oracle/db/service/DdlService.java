package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Metadata comment operations. Identifiers are validated and quoted by
 * {@link SqlIdentifiers} before assembly, so identifier injection is blocked.
 * Safety for the statement shape is enforced by the least-privilege Oracle
 * user, which has no DDL/DCL privileges; a structural statement is rejected by
 * Oracle (for example {@code ORA-01031: insufficient privileges}).
 * <p>
 * Only {@code COMMENT ON ...} metadata comments are exposed as MCP tools. The
 * CREATE-style helper methods below are retained for internal compatibility and
 * are not reachable from the MCP surface; they would fail against the
 * least-privilege user unless a DBA granted explicit DDL privileges.
 */
@Service
public class DdlService {

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer that executes the statement
	 */
	public DdlService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Applies a table-level comment ({@code COMMENT ON TABLE ... IS ...}).
	 *
	 * @param schema  owner of the table; validated by {@link SqlIdentifiers#qualified}
	 * @param table   table name; validated by {@link SqlIdentifiers#qualified}
	 * @param comment free-text comment; single quotes are escaped by
	 *                {@link SqlIdentifiers#stringLiteral}
	 * @return a confirmation message
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String commentOnTable(String schema, String table, String comment) {
		String sql = "COMMENT ON TABLE " + SqlIdentifiers.qualified(schema, table)
				+ " IS " + SqlIdentifiers.stringLiteral(comment);
		executeChecked(sql);
		return "Comment applied to table " + schema + "." + table;
	}

	/**
	 * Applies a column-level comment ({@code COMMENT ON COLUMN ... IS ...}).
	 *
	 * @param schema  owner of the table
	 * @param table   table name
	 * @param column  column name; validated by {@link SqlIdentifiers#quote}
	 * @param comment free-text comment
	 * @return a confirmation message
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String commentOnColumn(String schema, String table, String column, String comment) {
		String sql = "COMMENT ON COLUMN " + SqlIdentifiers.qualified(schema, table) + "."
				+ SqlIdentifiers.quote(column) + " IS " + SqlIdentifiers.stringLiteral(comment);
		executeChecked(sql);
		return "Comment applied to column " + schema + "." + table + "." + column;
	}

	/**
	 * Creates or replaces a view ({@code CREATE OR REPLACE VIEW ... AS <select>}).
	 * Not exposed as an MCP tool; requires DDL privileges the least-privilege
	 * user does not have.
	 *
	 * @param schema     owner of the new view
	 * @param view       view name
	 * @param selectBody the view body — must be a {@code SELECT} statement
	 *                   (validated by prefix check; the full body is passed
	 *                   verbatim to Oracle, which parses it as part of CREATE VIEW)
	 * @return a confirmation message
	 * @throws IllegalArgumentException           if {@code selectBody} is blank or
	 *         does not start with {@code SELECT}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String createOrReplaceView(String schema, String view, String selectBody) {
		if (selectBody == null || selectBody.isBlank()
				|| !selectBody.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")) {
			throw new IllegalArgumentException("selectBody must be a SELECT statement.");
		}
		String sql = "CREATE OR REPLACE VIEW " + SqlIdentifiers.qualified(schema, view)
				+ " AS " + selectBody;
		executeChecked(sql);
		return "View " + schema + "." + view + " created (or replaced).";
	}

	/**
	 * Creates a (public or private) synonym. Not exposed as an MCP tool.
	 *
	 * @param schema       owner of the new synonym
	 * @param synonym      synonym name
	 * @param targetSchema owner of the referenced object
	 * @param targetName   referenced object name
	 * @param isPublic     if {@code true}, creates a {@code PUBLIC SYNONYM}
	 *                     (requires {@code CREATE PUBLIC SYNONYM} privilege)
	 * @return a confirmation message
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String createSynonym(String schema, String synonym, String targetSchema, String targetName, boolean isPublic) {
		String sql = "CREATE " + (isPublic ? "PUBLIC " : "")
				+ "SYNONYM " + SqlIdentifiers.qualified(schema, synonym)
				+ " FOR " + SqlIdentifiers.qualified(targetSchema, targetName);
		executeChecked(sql);
		return "Synonym " + (isPublic ? "PUBLIC " : "") + schema + "." + synonym + " created.";
	}

	/**
	 * Creates a sequence with optional {@code START WITH/INCREMENT BY/CACHE/CYCLE/ORDER}.
	 * Not exposed as an MCP tool.
	 *
	 * @param schema      owner of the new sequence
	 * @param sequence    sequence name
	 * @param startWith   initial value; {@code null} omits the clause (Oracle default 1)
	 * @param incrementBy step between values; {@code null} omits the clause; must be non-zero
	 * @param cache       cache size; values below 2 fall back to {@code NOCACHE}
	 * @param cycle       if {@code true}, the sequence wraps around at its bounds
	 * @param order       if {@code true}, guarantees ordering in RAC environments
	 * @return a confirmation message
	 * @throws IllegalArgumentException           if {@code incrementBy} is zero
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String createSequence(String schema, String sequence, Long startWith, Long incrementBy,
								 Long cache, boolean cycle, boolean order) {
		if (incrementBy != null && incrementBy == 0) {
			throw new IllegalArgumentException("incrementBy must be non-zero (Oracle INCREMENT BY 0 is invalid).");
		}
		StringBuilder sb = new StringBuilder("CREATE SEQUENCE ")
				.append(SqlIdentifiers.qualified(schema, sequence));
		if (startWith != null) sb.append(" START WITH ").append(startWith);
		if (incrementBy != null) sb.append(" INCREMENT BY ").append(incrementBy);
		if (cache != null && cache >= 2) {
			sb.append(" CACHE ").append(cache);
		} else {
			sb.append(" NOCACHE");
		}
		sb.append(cycle ? " CYCLE" : " NOCYCLE");
		sb.append(order ? " ORDER" : " NOORDER");
		executeChecked(sb.toString());
		return "Sequence " + schema + "." + sequence + " created.";
	}

	/**
	 * Creates a B-tree (or unique) index on one or more columns. Not exposed as
	 * an MCP tool.
	 *
	 * @param schema      owner of the new index
	 * @param index       index name
	 * @param tableSchema owner of the target table
	 * @param table       target table name
	 * @param columns     at least one column to index; each is validated and quoted
	 * @param unique      if {@code true}, creates a {@code UNIQUE INDEX}
	 * @return a confirmation message
	 * @throws IllegalArgumentException           if {@code columns} is null or empty
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String createIndex(String schema, String index, String tableSchema, String table,
							  List<String> columns, boolean unique) {
		if (columns == null || columns.isEmpty()) {
			throw new IllegalArgumentException("At least one column is required.");
		}
		String cols = SqlIdentifiers.quoteCsv(columns);
		String sql = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
				+ SqlIdentifiers.qualified(schema, index) + " ON "
				+ SqlIdentifiers.qualified(tableSchema, table) + " (" + cols + ")";
		executeChecked(sql);
		return (unique ? "Unique index " : "Index ") + schema + "." + index + " created on "
				+ tableSchema + "." + table + ".";
	}

	/**
	 * Creates a materialized-view log on a table. Not exposed as an MCP tool.
	 *
	 * @param schema             owner of the target table
	 * @param table              target table name
	 * @param withRowid          if {@code true}, include {@code WITH ROWID}
	 * @param withPrimaryKey     if {@code true}, include {@code WITH PRIMARY KEY}
	 * @param includingNewValues if {@code true}, append {@code INCLUDING NEW VALUES}
	 * @return a confirmation message
	 * @throws IllegalArgumentException           if both {@code withRowid} and
	 *         {@code withPrimaryKey} are {@code false}
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	public String createMviewLog(String schema, String table, boolean withRowid, boolean withPrimaryKey,
								 boolean includingNewValues) {
		if (!withRowid && !withPrimaryKey) {
			throw new IllegalArgumentException(
					"At least one of withRowid or withPrimaryKey is required (Oracle needs PRIMARY KEY or ROWID).");
		}
		StringBuilder sb = new StringBuilder("CREATE MATERIALIZED VIEW LOG ON ")
				.append(SqlIdentifiers.qualified(schema, table));
		StringBuilder with = new StringBuilder();
		if (withPrimaryKey) with.append(" PRIMARY KEY");
		if (withRowid) with.append(with.isEmpty() ? " ROWID" : ", ROWID");
		if (!with.isEmpty()) sb.append(" WITH").append(with);
		if (includingNewValues) sb.append(" INCLUDING NEW VALUES");
		executeChecked(sb.toString());
		return "Materialized view log created on " + schema + "." + table + ".";
	}

	/**
	 * Executes an assembled statement against Oracle. Safety is enforced by the
	 * least-privilege Oracle user, which has no DDL/DCL privileges; a structural
	 * statement is rejected by Oracle.
	 *
	 * @param sql the fully assembled statement (identifiers already quoted)
	 * @throws org.springframework.dao.DataAccessException on any Oracle error
	 */
	private void executeChecked(String sql) {
		db.execute(sql);
	}
}
