package com.zademy.mcp.oracle.db.tools;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the bundled Oracle SQL authoring rules from the classpath and caches
 * them for the lifetime of the server. The rules travel inside the packaged
 * jar ({@code src/main/resources/mcp/oracle-sql-authoring-rules.md}); the
 * server never depends on an external file.
 *
 * <p>Consumed by the {@code oracle-sql-style} prompt and by the server
 * {@code instructions}. If the resource cannot be read (it always can in a
 * normal build), {@link #fullRules()} returns a short fallback notice instead
 * of throwing, so a prompt call never crashes the tool.
 */
@Component
public class SqlAuthoringRules {

	/** Classpath location of the bundled rules markdown. */
	static final String RESOURCE_PATH = "mcp/oracle-sql-authoring-rules.md";

	private final String rules;

	/**
	 * Reads and caches the rules at construction time (startup).
	 */
	public SqlAuthoringRules() {
		this.rules = loadFromClasspath(RESOURCE_PATH);
	}

	private static String loadFromClasspath(String path) {
		try {
			return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(Oracle SQL authoring rules unavailable: " + e.getMessage() + ")";
		}
	}

	/**
	 * @return the full authoring-rules markdown, cached at startup
	 */
	public String fullRules() {
		return rules;
	}
}
