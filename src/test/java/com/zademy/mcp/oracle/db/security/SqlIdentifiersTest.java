package com.zademy.mcp.oracle.db.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven tests for {@link SqlIdentifiers}. Security-critical: pins exactly
 * which identifiers are accepted, which are rejected (including injection
 * payloads), and that quoting/escaping produces the expected SQL fragments.
 */
class SqlIdentifiersTest {

	@Nested
	@DisplayName("validate accepts valid identifiers")
	class ValidateAccepts {

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {
				"EMP",
				"EMPLOYEES",
				"col_1",
				"col$name",
				"col#tag",
				"A",
				"MixedCase",
				"a1b2c3",
				"NAME_WITH_$OK",
				"HAS_#HASH"
		})
		void validIdentifiersAreAccepted(String name) {
			assertThat(SqlIdentifiers.validate(name)).isEqualTo(name);
		}

		@Test
		void maxLength128IsAccepted() {
			String name = "A".repeat(128);
			assertThat(SqlIdentifiers.validate(name)).isEqualTo(name);
		}
	}

	@Nested
	@DisplayName("validate rejects invalid identifiers")
	class ValidateRejects {

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {
				"",                         // empty
				"1col",                     // starts with digit
				"col x",                    // contains space
				"col\"q",                   // contains double quote
				"col;d",                    // semicolon (statement smuggling)
				"col--",                    // SQL line comment
				"col/*x*/",                 // SQL block comment
				"col)",                     // closing paren
				"col'",                     // single quote
				"col.tab",                  // dot
				"col,name",                 // comma
				"col\\x",                   // backslash
				"x); DROP TABLE y--",       // injection payload
				"x' OR '1'='1"              // injection payload
		})
		void invalidIdentifiersAreRejected(String name) {
			assertThatThrownBy(() -> SqlIdentifiers.validate(name))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Invalid Oracle identifier");
		}

		@Test
		void nullIsRejected() {
			assertThatThrownBy(() -> SqlIdentifiers.validate(null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void tooLong129CharsIsRejected() {
			String name = "A".repeat(129);
			assertThatThrownBy(() -> SqlIdentifiers.validate(name))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("quote / qualified / quoteCsv")
	class Quoting {

		@Test
		void quoteWrapsInDoubleQuotes() {
			assertThat(SqlIdentifiers.quote("EMP")).isEqualTo("\"EMP\"");
		}

		@Test
		void quoteValidatesFirst() {
			assertThatThrownBy(() -> SqlIdentifiers.quote("col x"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void qualifiedRendersTwoPartName() {
			assertThat(SqlIdentifiers.qualified("HR", "EMPLOYEES")).isEqualTo("\"HR\".\"EMPLOYEES\"");
		}

		@Test
		void quoteCsvJoinsQuoted() {
			assertThat(SqlIdentifiers.quoteCsv(List.of("A", "B", "C")))
					.isEqualTo("\"A\", \"B\", \"C\"");
		}

		@Test
		void quoteCsvEmptyListProducesEmptyString() {
			assertThat(SqlIdentifiers.quoteCsv(List.of())).isEqualTo("");
		}

		@Test
		void quoteCsvValidatesEveryElement() {
			assertThatThrownBy(() -> SqlIdentifiers.quoteCsv(List.of("A", "bad name")))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("quoteTrusted / qualifiedTrusted (DB-sourced)")
	class TrustedQuoting {

		@Test
		void quoteTrustedSkipsValidation() {
			assertThat(SqlIdentifiers.quoteTrusted("a b")).isEqualTo("\"a b\"");
		}

		@Test
		void quoteTrustedAcceptsArbitraryChars() {
			assertThat(SqlIdentifiers.quoteTrusted("weird;name")).isEqualTo("\"weird;name\"");
		}

		@Test
		void qualifiedTrustedRendersWithoutValidation() {
			assertThat(SqlIdentifiers.qualifiedTrusted("HR", "my table"))
					.isEqualTo("\"HR\".\"my table\"");
		}
	}

	@Nested
	@DisplayName("stringLiteral")
	class StringLiteral {

		@Test
		void doublesSingleQuotes() {
			assertThat(SqlIdentifiers.stringLiteral("it's")).isEqualTo("'it''s'");
		}

		@Test
		void nullBecomesSqlNull() {
			assertThat(SqlIdentifiers.stringLiteral(null)).isEqualTo("NULL");
		}

		@Test
		void plainStringIsWrappedInQuotes() {
			assertThat(SqlIdentifiers.stringLiteral("hello")).isEqualTo("'hello'");
		}

		@Test
		void multipleQuotesAllDoubled() {
			assertThat(SqlIdentifiers.stringLiteral("a'b'c")).isEqualTo("'a''b''c'");
		}
	}
}
