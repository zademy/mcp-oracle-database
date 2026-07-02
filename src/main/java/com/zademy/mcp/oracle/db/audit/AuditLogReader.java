package com.zademy.mcp.oracle.db.audit;

import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only access to per-session audit log files written by
 * {@link AuditLogWriter}.
 *
 * <p>Provides three operations consumed by the
 * {@code oracle://audit/session/{id}} MCP resource and by completions:
 * <ul>
 *   <li>{@link #readSession(String)} — full text content of one session log</li>
 *   <li>{@link #listSessionIds()} — all known session IDs in the audit directory</li>
 *   <li>{@link #currentSessionId()} — the live session ID</li>
 * </ul>
 *
 * <p><b>Fully inert when audit is disabled.</b> When
 * {@code oracle.mcp.audit.enabled=false} (the default), every method returns
 * an empty result and no I/O is performed.
 *
 * @param props  the {@code oracle.mcp.*} configuration
 * @param writer the live session writer (provides the current session ID)
 */
@Component
public class AuditLogReader {

	private final OracleMcpProperties props;
	private final AuditLogWriter writer;

	public AuditLogReader(OracleMcpProperties props, AuditLogWriter writer) {
		this.props = props;
		this.writer = writer;
	}

	/**
	 * The current session's 8-character ID.
	 *
	 * @return the session ID (always non-null, even when audit is disabled)
	 */
	public String currentSessionId() {
		return writer.sessionId();
	}

	/**
	 * Reads the full text content of the audit log for the given session ID.
	 *
	 * <p>Searches the audit directory for a file whose name contains
	 * {@code -<sessionId>-log.txt}. Returns {@link Optional#empty()} when
	 * the id is not an 8-character hex, audit is disabled, the directory
	 * does not exist, or no matching file is found.
	 *
	 * @param sessionId the 8-character hexadecimal session ID
	 * @return the file content, or empty if invalid / not found / audit disabled
	 */
	public Optional<String> readSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()
				|| !sessionId.matches("[0-9a-f]{8}")) {
			return Optional.empty();
		}
		if (!isAuditEnabled()) {
			return Optional.empty();
		}
		Path dir = auditDir();
		if (dir == null || !Files.isDirectory(dir)) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files
					.filter(p -> p.getFileName().toString().endsWith("-log.txt"))
					.filter(p -> p.getFileName().toString().contains("-" + sessionId + "-"))
					.findFirst()
					.map(this::readAllText);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/**
	 * Lists all distinct session IDs found in the audit directory.
	 *
	 * <p>Extracts the 8-character session ID from filenames matching the
	 * pattern {@code <serverName>-<dd-MM-yyyy>-<sessionId8>-log.txt}.
	 *
	 * @return a sorted, distinct list of session IDs; empty when audit is
	 *         disabled or the directory is missing
	 */
	public List<String> listSessionIds() {
		if (!isAuditEnabled()) {
			return List.of();
		}
		Path dir = auditDir();
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files
					.map(p -> p.getFileName().toString())
					.filter(name -> name.endsWith("-log.txt"))
					.map(this::extractSessionId)
					.filter(Objects::nonNull)
					.distinct()
					.sorted()
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private boolean isAuditEnabled() {
		return props.audit() != null && props.audit().enabled();
	}

	private Path auditDir() {
		if (props.audit() == null || props.audit().dir() == null || props.audit().dir().isBlank()) {
			return null;
		}
		return Path.of(props.audit().dir()).toAbsolutePath().normalize();
	}

	private String readAllText(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException e) {
			return "(Unable to read audit log file: " + e.getMessage() + ")";
		}
	}

	private String extractSessionId(String filename) {
		int suffixIdx = filename.lastIndexOf("-log.txt");
		if (suffixIdx < 0) {
			return null;
		}
		String before = filename.substring(0, suffixIdx);
		int lastDash = before.lastIndexOf('-');
		if (lastDash < 0) {
			return null;
		}
		String candidate = before.substring(lastDash + 1);
		if (candidate.length() == 8 && candidate.matches("[0-9a-f]{8}")) {
			return candidate;
		}
		return null;
	}
}
