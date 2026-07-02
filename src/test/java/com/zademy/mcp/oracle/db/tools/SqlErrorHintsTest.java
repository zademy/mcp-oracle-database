package com.zademy.mcp.oracle.db.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SqlErrorHints}. Pure — no Spring, no database. Covers
 * the ORA-code detection map, the {@code enrich()} output shape, and the
 * null/unknown fallbacks.
 */
@DisplayName("SqlErrorHints")
class SqlErrorHintsTest {

	static Stream<Arguments> knownCodes() {
		return Stream.of(
				Arguments.of("ORA-00923: FROM keyword not found where expected", "AS"),
				Arguments.of("ORA-00936: missing expression", "comma"),
				Arguments.of("ORA-00904: \"BAD_COL\": invalid identifier", "describe_table"),
				Arguments.of("ORA-00911: invalid character", "semicolon"),
				Arguments.of("ORA-00933: SQL command not properly ended", "LIMIT"),
				Arguments.of("ORA-00907: missing right parenthesis", "parenthes"),
				Arguments.of("ORA-01756: quoted string not properly terminated", "literal"),
				Arguments.of("ORA-01790: expression must have same datatype", "UNION"),
				Arguments.of("ORA-00942: table or view does not exist", "grant"),
				Arguments.of("ORA-01031: insufficient privileges", "DBA")
		);
	}

	@ParameterizedTest(name = "[{0}] -> hint contains \"{1}\"")
	@MethodSource("knownCodes")
	@DisplayName("maps each known ORA code to a relevant hint")
	void mapsKnownCodesToHints(String message, String expectedFragment) {
		String hint = SqlErrorHints.hintFor(message);

		assertThat(hint).as("hint must not be blank for a known code").isNotBlank();
		assertThat(hint).containsIgnoringCase(expectedFragment);
	}

	@Test
	@DisplayName("detection is case-insensitive")
	void detectionIsCaseInsensitive() {
		assertThat(SqlErrorHints.hintFor("ora-00923 something"))
				.as("lower-case code must still match").isNotBlank();
	}

	@Test
	@DisplayName("enrich() prefixes the message and appends a hint + prompt pointer")
	void enrichBuildsFullString() {
		String result = SqlErrorHints.enrich("ORA-00923: FROM keyword not found where expected");

		assertThat(result).startsWith("Oracle error: ");
		assertThat(result).contains("ORA-00923");
		assertThat(result).contains("↳");
		assertThat(result).containsIgnoringCase("oracle-sql-style");
	}

	@Test
	@DisplayName("enrich() leaves unknown errors untouched apart from the prefix")
	void enrichLeavesUnknownErrorsUntouched() {
		String result = SqlErrorHints.enrich("ORA-02019: connection description for remote database not found");

		assertThat(result).isEqualTo("Oracle error: ORA-02019: connection description for remote database not found");
		assertThat(result).doesNotContain("↳");
	}

	@Test
	@DisplayName("hintFor() returns blank for null, blank and unknown input")
	void hintForBlankOnUnknown() {
		assertThat(SqlErrorHints.hintFor(null)).isEmpty();
		assertThat(SqlErrorHints.hintFor("   ")).isEmpty();
		assertThat(SqlErrorHints.hintFor("ORA-99999: not in the map")).isEmpty();
	}

	@Test
	@DisplayName("enrich() is null-safe")
	void enrichIsNullSafe() {
		assertThat(SqlErrorHints.enrich(null)).isEqualTo("Oracle error: ");
	}

	@Test
	@DisplayName("enrich() handles a realistic full Spring/JDBC message")
	void enrichHandlesRealisticMessage() {
		String msg = "PreparedStatementCallback; bad SQL grammar [SELECT a b FROM t]; "
				+ "nested exception is java.sql.SQLSyntaxErrorException: ORA-00923: "
				+ "FROM keyword not found where expected";

		String result = SqlErrorHints.enrich(msg);

		assertThat(result).contains("ORA-00923");
		assertThat(result).containsIgnoringCase("reserved");
	}
}
