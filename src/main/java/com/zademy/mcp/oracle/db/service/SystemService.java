package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.ConnectionInfo;
import com.zademy.mcp.oracle.db.model.HealthCheck;
import com.zademy.mcp.oracle.db.model.HealthReport;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports database connectivity, identity, and overall readiness. Used by the
 * {@code test_connection} and {@code oracle_mcp_health_report} tools.
 */
@Service
public class SystemService {

	private final OracleDataAccess db;
	private final OracleMcpProperties props;

	/**
	 * Spring-injected constructor.
	 *
	 * @param db    the data access layer (provides the read-only queries)
	 * @param props the {@code oracle.mcp.*} runtime configuration
	 */
	public SystemService(OracleDataAccess db, OracleMcpProperties props) {
		this.db = db;
		this.props = props;
	}

	/**
	 * Reports the Oracle version and the connection identity in a single
	 * round-trip. Collapsing what used to be five separate statements
	 * ({@code v$version} plus four {@code SYS_CONTEXT} calls) into one
	 * keeps pool pressure low under the small simultaneous-connection budget.
	 *
	 * @return a {@link ConnectionInfo} populated with version, db name, current
	 *         user, current schema, and instance name
	 * @throws org.springframework.dao.DataAccessException if the database is unreachable
	 */
	public ConnectionInfo connectionInfo() {
		Map<String, Object> row = db.queryForList("""
				SELECT (SELECT banner FROM v$version WHERE ROWNUM = 1) AS version,
				       SYS_CONTEXT('USERENV', 'DB_NAME')       AS db_name,
				       SYS_CONTEXT('USERENV', 'SESSION_USER')   AS current_user,
				       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS current_schema,
				       SYS_CONTEXT('USERENV', 'INSTANCE_NAME')  AS instance_name
				FROM   dual
				""", Map.of()).stream().findFirst().orElse(Map.of());
		return new ConnectionInfo(
				stringOf(row.get("version")),
				stringOf(row.get("db_name")),
				stringOf(row.get("current_user")),
				stringOf(row.get("current_schema")),
				stringOf(row.get("instance_name")));
	}

	private static String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * Runs a battery of readiness probes and returns an aggregated report.
	 * Each probe is independent: a failure in one does not prevent the others
	 * from executing. If the database is completely unreachable the first probe
	 * records the failure and the remaining probes are marked FAIL with the
	 * connectivity error.
	 *
	 * @return a {@link HealthReport} with the overall status and per-probe details
	 */
	public HealthReport healthReport() {
		List<HealthCheck> checks = new ArrayList<>();
		String databaseName = null;
		boolean down = false;

		// 1. Connectivity probe — a single statement both proves the connection
		//    is usable and fetches the database name, avoiding a second round-trip.
		try {
			databaseName = db.queryForObject(
					"SELECT SYS_CONTEXT('USERENV', 'DB_NAME') FROM dual", Map.of(), String.class);
			checks.add(new HealthCheck("database-connectivity", "PASS", "Connection established"));
		} catch (DataAccessException e) {
			checks.add(new HealthCheck("database-connectivity", "FAIL",
					"Cannot reach database: " + rootMessage(e)));
			down = true;
		}

		// 2. Version probe
		if (!down) {
			try {
				String version = db.queryForObject(
						"SELECT banner FROM v$version WHERE ROWNUM = 1", String.class);
				checks.add(new HealthCheck("database-version", "PASS", version));
			} catch (DataAccessException e) {
				checks.add(new HealthCheck("database-version", "WARN",
						"Could not read version: " + rootMessage(e)));
			}
		}

		// 3. Catalog-role probe
		if (!down) {
			try {
				Integer count = db.queryForObject(
						"SELECT COUNT(*) FROM user_role_privs WHERE granted_role = 'SELECT_CATALOG_ROLE'",
						Integer.class);
				if (count != null && count > 0) {
					checks.add(new HealthCheck("catalog-role", "PASS",
							"SELECT_CATALOG_ROLE granted (" + count + " role(s))"));
				} else {
					checks.add(new HealthCheck("catalog-role", "WARN",
							"SELECT_CATALOG_ROLE not found; dictionary views may be restricted"));
				}
			} catch (DataAccessException e) {
				checks.add(new HealthCheck("catalog-role", "WARN",
						"Cannot verify role: " + rootMessage(e)));
			}
		}

		// 4. Invalid-objects probe (current schema only)
		if (!down) {
			try {
				Integer invalid = db.queryForObject(
						"SELECT COUNT(*) FROM user_objects WHERE status = 'INVALID'",
						Integer.class);
				if (invalid != null && invalid == 0) {
					checks.add(new HealthCheck("invalid-objects", "PASS",
							"No invalid objects in current schema"));
				} else if (invalid != null) {
					checks.add(new HealthCheck("invalid-objects", "WARN",
							invalid + " invalid object(s) in current schema"));
				}
			} catch (DataAccessException e) {
				checks.add(new HealthCheck("invalid-objects", "WARN",
						"Cannot check invalid objects: " + rootMessage(e)));
			}
		}

		// 5. Configuration probe (always informational)
		checks.add(new HealthCheck("server-config", "PASS",
				"max-rows=" + props.maxRows()
						+ ", query-timeout=" + props.queryTimeoutSeconds() + "s"
						+ ", audit=" + (props.audit().enabled() ? "enabled" : "disabled")));

		// Aggregate
		String overall;
		if (down) {
			overall = "DOWN";
		} else {
			boolean hasWarn = checks.stream().anyMatch(c -> "WARN".equals(c.status()));
			overall = hasWarn ? "DEGRADED" : "UP";
		}

		return new HealthReport(overall, databaseName, checks);
	}

	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String msg = cause.getMessage();
		return msg == null ? e.getClass().getSimpleName() : msg;
	}
}
