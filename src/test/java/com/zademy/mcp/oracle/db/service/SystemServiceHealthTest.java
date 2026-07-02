package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.config.AuditProperties;
import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import com.zademy.mcp.oracle.db.model.HealthReport;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SystemService#healthReport()}. Mocks
 * {@link OracleDataAccess} to simulate connectivity, role, and
 * invalid-object states.
 */
@ExtendWith(MockitoExtension.class)
class SystemServiceHealthTest {

	@Mock
	private OracleDataAccess db;

	private SystemService service;

	private final OracleMcpProperties props = new OracleMcpProperties(
			500, 30, 10, new AuditProperties(false, "./logs"));

	@BeforeEach
	void setUp() {
		service = new SystemService(db, props);
	}

	@Test
	@DisplayName("Health report: DOWN when database is unreachable")
	void healthReport_dbDown() {
		when(db.queryForObject(any(String.class), any(Map.class), eq(String.class)))
				.thenThrow(new DataAccessResourceFailureException("Connection refused"));

		HealthReport report = service.healthReport();

		assertThat(report.overallStatus()).isEqualTo("DOWN");
		assertThat(report.databaseName()).isNull();
		assertThat(report.checks()).hasSizeGreaterThanOrEqualTo(1);
		assertThat(report.checks().get(0).name()).isEqualTo("database-connectivity");
		assertThat(report.checks().get(0).status()).isEqualTo("FAIL");
	}

	@Test
	@DisplayName("Health report: UP when all probes pass")
	void healthReport_allPass() {
		when(db.queryForObject(any(String.class), any(Map.class), eq(String.class)))
				.thenReturn("ORCL");                                 // DB_NAME (connectivity probe)
		when(db.queryForObject(any(String.class), eq(String.class)))
				.thenReturn("Oracle 19c");                           // version banner
		when(db.queryForObject(any(String.class), eq(Integer.class)))
				.thenReturn(1)    // catalog-role count
				.thenReturn(0);   // invalid-objects count

		HealthReport report = service.healthReport();

		assertThat(report.overallStatus()).isEqualTo("UP");
		assertThat(report.databaseName()).isEqualTo("ORCL");
		assertThat(report.checks()).extracting("status")
				.containsOnly("PASS");
	}

	@Test
	@DisplayName("Health report: DEGRADED when catalog-role is missing")
	void healthReport_catalogRoleMissing() {
		when(db.queryForObject(any(String.class), any(Map.class), eq(String.class)))
				.thenReturn("ORCL");                                 // DB_NAME (connectivity probe)
		when(db.queryForObject(any(String.class), eq(String.class)))
				.thenReturn("Oracle 19c");                           // version banner
		when(db.queryForObject(any(String.class), eq(Integer.class)))
				.thenReturn(0)    // catalog-role count (missing)
				.thenReturn(0);   // invalid-objects count

		HealthReport report = service.healthReport();

		assertThat(report.overallStatus()).isEqualTo("DEGRADED");
		assertThat(report.checks()).filteredOn(c -> "catalog-role".equals(c.name()))
				.singleElement()
				.extracting("status")
				.isEqualTo("WARN");
	}

	@Test
	@DisplayName("Health report: DEGRADED when invalid objects exist")
	void healthReport_invalidObjects() {
		when(db.queryForObject(any(String.class), any(Map.class), eq(String.class)))
				.thenReturn("ORCL");                                 // DB_NAME (connectivity probe)
		when(db.queryForObject(any(String.class), eq(String.class)))
				.thenReturn("Oracle 19c");                           // version banner
		when(db.queryForObject(any(String.class), eq(Integer.class)))
				.thenReturn(1)    // catalog-role count
				.thenReturn(3);   // 3 invalid objects

		HealthReport report = service.healthReport();

		assertThat(report.overallStatus()).isEqualTo("DEGRADED");
		assertThat(report.checks()).filteredOn(c -> "invalid-objects".equals(c.name()))
				.singleElement()
				.extracting("status")
				.isEqualTo("WARN");
	}

	@Test
	@DisplayName("Health report: config probe always present and informational")
	void healthReport_configAlwaysPresent() {
		when(db.queryForObject(any(String.class), any(Map.class), eq(String.class)))
				.thenThrow(new DataAccessResourceFailureException("down"));

		HealthReport report = service.healthReport();

		assertThat(report.checks()).filteredOn(c -> "server-config".equals(c.name()))
				.singleElement()
				.satisfies(c -> {
					assertThat(c.status()).isEqualTo("PASS");
					assertThat(c.detail()).contains("max-rows=500");
					assertThat(c.detail()).contains("audit=disabled");
				});
	}
}
