package com.zademy.mcp.oracle.db.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * JDBC setup. Configures a single {@link JdbcTemplate} with a global query
 * timeout (defence against runaway queries) and a max-rows cap (defence against
 * huge result sets). A {@link NamedParameterJdbcTemplate} wraps it for the
 * internal metadata queries that use named bind parameters.
 */
@Configuration
@EnableConfigurationProperties(OracleMcpProperties.class)
public class JdbcConfig {

	/**
	 * Builds the primary {@link JdbcTemplate} used by every service.
	 *
	 * <p>Applies two safety caps from {@link OracleMcpProperties}:
	 * <ul>
	 *   <li>{@code queryTimeoutSeconds} — aborts any SQL that runs longer than the
	 *       configured wall-clock budget, protecting the Hikari pool from being
	 *       starved by runaway queries.</li>
	 *   <li>{@code maxRows} — caps the number of rows returned by any single
	 *       {@code queryForList}/{@code query} call, bounding memory use when an
	 *       AI-generated SELECT accidentally targets a very large table.</li>
	 * </ul>
	 *
	 * @param dataSource the auto-configured HikariCP data source
	 * @param props      the {@code oracle.mcp.*} tuning properties
	 * @return a configured {@link JdbcTemplate} bound to the Oracle data source
	 */
	@Bean
	public JdbcTemplate jdbcTemplate(DataSource dataSource, OracleMcpProperties props) {
		JdbcTemplate template = new JdbcTemplate(dataSource);
		template.setQueryTimeout(props.queryTimeoutSeconds());
		template.setMaxRows(props.maxRows());
		return template;
	}

	/**
	 * Wraps the {@link JdbcTemplate} in a {@link NamedParameterJdbcTemplate} so
	 * internal metadata queries can bind parameters by name ({@code :schema},
	 * {@code :table}) instead of by positional {@code ?}.
	 *
	 * <p>Named parameters are used exclusively by the read-only metadata path
	 * (e.g. {@code ALL_TABLES}, {@code ALL_SOURCE}) where the set of bind values
	 * is built dynamically from user input.
	 *
	 * @param jdbcTemplate the primary template configured above
	 * @return a named-parameter wrapper around the same {@link JdbcTemplate}
	 */
	@Bean
	public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
		return new NamedParameterJdbcTemplate(jdbcTemplate);
	}
}
