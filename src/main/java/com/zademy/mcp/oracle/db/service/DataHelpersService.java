package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import com.zademy.mcp.oracle.db.security.SqlIdentifiers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Convenience data helpers that compose safe SQL from validated identifiers.
 * The assembled statements are simple fixed-shape queries; we deliberately do
 * NOT accept arbitrary WHERE/HAVING predicates because identifier validation
 * cannot sanitise free-form predicate text. Tools that need a custom predicate
 * should use {@code run_query} instead, where the AI-assembled SQL is visible
 * to the operator. Safety is enforced by the least-privilege Oracle user.
 */
@Service
public class DataHelpersService {

	private final OracleDataAccess db;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db the data access layer that executes the statement
	 */
	public DataHelpersService(OracleDataAccess db) {
		this.db = db;
	}

	/**
	 * Count all rows in a table. For a filtered count, use the {@code run_query}
	 * tool with a {@code SELECT COUNT(*) FROM ... WHERE ...} statement — that
	 * path surfaces the full SQL to the operator instead of burying a predicate
	 * inside a helper.
	 */
	public long countRows(String schema, String table) {
		String sql = "SELECT COUNT(*) FROM " + SqlIdentifiers.qualified(schema, table);
		Long n = db.queryForObject(sql, Long.class);
		return n == null ? 0L : n;
	}

	/**
	 * Return the next value of a sequence. Note: consuming NEXTVAL advances the
	 * sequence — this is a side-effecting read.
	 */
	public long getNextSequenceValue(String schema, String sequence) {
		String sql = "SELECT " + SqlIdentifiers.qualified(schema, sequence) + ".NEXTVAL FROM dual";
		Long n = db.queryForObject(sql, Long.class);
		return n == null ? 0L : n;
	}

	/**
	 * Find orphan rows violating one or all foreign keys on a table. Returns a
	 * list of maps with constraint name, columns and orphan count. An empty list
	 * means the referential integrity is intact.
	 */
	public List<Map<String, Object>> validateFkIntegrity(String schema, String table, String constraint) {
		String childTableRef = SqlIdentifiers.qualified(schema, table);
		String fkFilter = constraint == null || constraint.isBlank()
				? ""
				: " AND c.constraint_name = '" + SqlIdentifiers.validate(constraint) + "'";

		List<Map<String, Object>> fks = db.queryForList("""
				SELECT c.constraint_name,
				       (SELECT LISTAGG(cc.column_name, ', ') WITHIN GROUP (ORDER BY cc.position)
				        FROM all_cons_columns cc
				        WHERE cc.owner = c.owner AND cc.constraint_name = c.constraint_name) AS child_columns,
				       c.r_owner AS parent_owner,
				       c.r_constraint_name AS parent_constraint,
				       (SELECT LISTAGG(rc.column_name, ', ') WITHIN GROUP (ORDER BY rc.position)
				        FROM all_cons_columns rc
				        WHERE rc.owner = c.r_owner AND rc.constraint_name = c.r_constraint_name) AS parent_columns
				FROM all_constraints c
				WHERE c.owner = :schema AND c.table_name = :table
				AND c.constraint_type = 'R'
				""" + fkFilter, Map.of("schema", schema, "table", table));

		List<Map<String, Object>> results = new ArrayList<>();
		for (Map<String, Object> fk : fks) {
			String constraintName = (String) fk.get("constraint_name");
			String childCols = (String) fk.get("child_columns");
			String parentOwner = (String) fk.get("parent_owner");
			String parentConstraint = (String) fk.get("parent_constraint");
			String parentCols = (String) fk.get("parent_columns");
			if (childCols == null || parentCols == null || parentOwner == null || parentConstraint == null) {
				continue;
			}

			String parentTableSql = """
					SELECT table_name FROM all_constraints
					WHERE owner = :owner AND constraint_name = :con
					""";
			String parentTable = db.queryForObject(parentTableSql,
					Map.of("owner", parentOwner, "con", parentConstraint), String.class);
			if (parentTable == null) continue;

			String parentTableRef = SqlIdentifiers.qualifiedTrusted(parentOwner, parentTable);
			String orphanSql = "SELECT COUNT(*) FROM " + childTableRef + " c"
					+ " WHERE (" + expandCols("c", childCols) + ") NOT IN"
					+ " (SELECT " + expandCols("p", parentCols) + " FROM " + parentTableRef + " p)";
			Long count = db.queryForObject(orphanSql, Long.class);
			results.add(Map.of(
					"constraint_name", constraintName,
					"child_columns", childCols,
					"parent_owner", parentOwner,
					"parent_table", parentTable,
					"parent_columns", parentCols,
					"orphan_count", count == null ? 0L : count));
		}
		return results;
	}

	/**
	 * Find duplicate rows by a set of columns. Returns the duplicate groups and
	 * their counts, filtered to groups with at least {@code minCount} rows
	 * (default 2). The {@code minCount} is a server-controlled integer; no
	 * arbitrary HAVING predicate is accepted because identifier validation
	 * cannot sanitise predicate text.
	 */
	public List<Map<String, Object>> findDuplicates(String schema, String table, List<String> columns, long minCount) {
		if (columns == null || columns.isEmpty()) {
			throw new IllegalArgumentException("At least one column is required.");
		}
		long threshold = minCount < 2 ? 2 : minCount;
		String cols = SqlIdentifiers.quoteCsv(columns);
		String tableRef = SqlIdentifiers.qualified(schema, table);
		String sql = "SELECT " + cols + ", COUNT(*) AS dup_count FROM " + tableRef
				+ " GROUP BY " + cols
				+ " HAVING COUNT(*) >= " + threshold
				+ " ORDER BY dup_count DESC";
		return db.queryForList(sql);
	}

	/**
	 * Returns the smallest positive integer in {@code 1..maxRange} that is not
	 * already present in {@code schema.table.column}. Optionally also excludes
	 * ids present in a second table (same schema, same column) for FK scenarios
	 * where an id must be free in two tables at once (e.g. a child that shares
	 * the parent's PK).
	 *
	 * <p>The series is generated in Java (not via {@code CONNECT BY} or a
	 * recursive CTE): those constructs can be rejected by the SQL parser. A
	 * plain fixed-shape {@code SELECT ... WHERE col BETWEEN 1 AND :range}
	 * fetches the existing ids; the loop then finds the first gap.
	 *
	 * <p>{@code maxRange} is a server-controlled integer clamped to
	 * {@code [1, 10000]} and concatenated as a numeric literal (no predicate
	 * text is accepted from the caller).
	 *
	 * @param schema       owner of the table
	 * @param table        table name
	 * @param column       numeric column to search for a free positive id
	 * @param maxRange     upper bound of the search window (clamped to 10000)
	 * @param excludeTable optional second table (same schema, same column) whose
	 *                     ids must also be avoided; {@code null}/blank disables it
	 * @return the first free id, or {@code null} if every value in range is taken
	 */
	public Long findFreeId(String schema, String table, String column, int maxRange, String excludeTable) {
		int range = maxRange < 1 ? 1 : Math.min(maxRange, 10000);
		String colRef = SqlIdentifiers.quote(column);
		Set<Long> used = collectIds(runIdQuery(schema, table, colRef, range));
		if (excludeTable != null && !excludeTable.isBlank()) {
			used.addAll(collectIds(runIdQuery(schema, excludeTable, colRef, range)));
		}
		for (long i = 1; i <= range; i++) {
			if (!used.contains(i)) {
				return i;
			}
		}
		return null;
	}

	private List<Map<String, Object>> runIdQuery(String schema, String table, String colRef, int range) {
		String tableRef = SqlIdentifiers.qualified(schema, table);
		String sql = "SELECT " + colRef + " FROM " + tableRef
				+ " WHERE " + colRef + " BETWEEN 1 AND " + range;
		return db.queryForList(sql);
	}

	private static Set<Long> collectIds(List<Map<String, Object>> rows) {
		Set<Long> ids = new HashSet<>();
		for (Map<String, Object> row : rows) {
			Object value = row.values().iterator().next();
			if (value instanceof Number n) {
				ids.add(n.longValue());
			}
		}
		return ids;
	}

	/**
	 * Expands a CSV of column names (as returned by {@code LISTAGG} in Oracle
	 * dictionary queries) into a comma-separated list of quoted, aliased
	 * references (e.g. {@code "A, B"} &rarr; {@code c."A", c."B"}).
	 *
	 * <p>Each column is validated with {@link SqlIdentifiers#validate} so a
	 * malicious dictionary value cannot smuggle SQL through.
	 *
	 * @param alias    the table alias to prefix (e.g. {@code "c"})
	 * @param csvCols  the raw CSV column list from Oracle's dictionary
	 * @return a comma-separated list of quoted, aliased column references
	 */
	private static String expandCols(String alias, String csvCols) {
		return Arrays.stream(csvCols.split(","))
				.map(String::trim)
				.map(SqlIdentifiers::validate)
				.map(c -> alias + ".\"" + c + "\"")
				.collect(Collectors.joining(", "));
	}
}
