package com.zademy.mcp.oracle.db.audit;

import com.zademy.mcp.oracle.db.config.AuditProperties;
import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes {@link AuditEntry} instances to a per-session plain-text {@code .txt}
 * file.
 *
 * <p><b>One file per MCP session.</b> The filename is fixed at bean
 * initialisation and never changes for the lifetime of the JVM:
 * {@code <serverName>-<dd-MM-yyyy>-<sessionId8>-log.txt}, for example
 * {@code mcp-oracle-db-27-06-2026-a1b2c3d4-log.txt}. All entries are appended
 * in arrival order under a single {@link ReentrantLock} so concurrent virtual
 * threads never interleave a block.
 *
 * <p><b>Inert when disabled.</b> If {@link AuditProperties#enabled()} is
 * {@code false}, {@link #append(AuditEntry)} returns immediately, no file is
 * ever created, and the lazy initialisation is skipped. This is the default.
 *
 * <p><b>Failure isolation.</b> Audit logging must never break an MCP response.
 * If an {@link IOException} occurs on the first write, the directory cannot be
 * created, or the file cannot be opened, the writer logs a single error to
 * SLF4J (stderr — never stdout, which is the JSON-RPC channel) and permanently
 * disables itself for the rest of the session so we do not spam the log on
 * every subsequent SQL.
 *
 * <p><b>STDIO safety.</b> Writes go to the filesystem only. Nothing is written
 * to stdout, preserving the JSON-RPC transport contract documented in
 * {@code AGENTS.md §9}.
 *
 * <p><b>Concurrency note.</b> The lock guards block integrity, not correctness
 * of individual {@code write} calls. MCP-level concurrency is modest; a single
 * lock is simplest and correct. If audit volume ever becomes a bottleneck this
 * can be swapped for a single-writer-thread draining a
 * {@link java.util.concurrent.BlockingQueue} without changing the public API.
 */
@Component
public class AuditLogWriter {

	private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
	private static final DateTimeFormatter ENTRY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
			.withZone(ZoneId.systemDefault());

	private final AuditProperties props;
	private final String serverName;
	private final String sessionId;

	private final ReentrantLock writeLock = new ReentrantLock();
	private BufferedWriter writer;
	private Path sessionFile;
	private boolean disabledForSession;

	/**
	 * Spring-injected constructor.
	 *
	 * <p>Takes the whole {@link OracleMcpProperties} (not the nested
	 * {@link AuditProperties}) to match the pattern used by
	 * {@link com.zademy.mcp.oracle.db.persistence.OracleDataAccess} and
	 * {@link com.zademy.mcp.oracle.db.config.JdbcConfig}: only the top-level
	 * record is registered as a Spring bean, so nested records must be reached
	 * via accessors. A {@code null} {@code audit} block (possible when a test
	 * context does not bind {@code oracle.mcp.audit.*}) is treated as
	 * "disabled" so the writer stays inert without failing the context.
	 *
	 * @param props     the {@code oracle.mcp.*} settings
	 * @param serverName MCP server name, resolved from
	 *                   {@code spring.ai.mcp.server.name} with a sane default;
	 *                   used as the filename prefix
	 */
	public AuditLogWriter(OracleMcpProperties props,
			@Value("${spring.ai.mcp.server.name:mcp-oracle-db}") String serverName) {
		AuditProperties audit = props.audit();
		this.props = audit != null ? audit : new AuditProperties(false, "./mcp-audit-logs");
		this.serverName = serverName;
		this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/**
	 * Resolves the absolute session filename once configuration is bound.
	 *
	 * <p>No file or directory is created here; that happens lazily on the first
	 * {@link #append(AuditEntry)} so a disabled or idle server touches nothing.
	 */
	@PostConstruct
	void resolveSessionFile() {
		if (!props.enabled()) {
			return;
		}
		String date = FILE_DATE.format(Instant.now().atZone(ZoneId.systemDefault()));
		this.sessionFile = Path.of(props.dir()).toAbsolutePath().normalize()
				.resolve(serverName + "-" + date + "-" + sessionId + "-log.txt");
	}

	/**
	 * Appends one {@link AuditEntry} to the session file as a human-readable
	 * block. Safe to call from any (virtual) thread.
	 *
	 * <p>No-op when audit is disabled, when the writer has been permanently
	 * disabled by an earlier I/O failure, or if the entry is {@code null}.
	 *
	 * @param entry the execution to record; if {@code null} the call is ignored
	 */
	public void append(AuditEntry entry) {
		if (entry == null) {
			return;
		}
		if (!props.enabled() || disabledForSession) {
			return;
		}
		writeLock.lock();
		try {
			ensureOpen();
			if (disabledForSession || writer == null) {
				return;
			}
			writer.write(render(entry));
			writer.flush();
		} catch (IOException e) {
			disableOnce("Failed to append audit entry", e);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Lazily opens the session file on first write, creating the directory
	 * tree as needed. Sets {@link #disabledForSession} on failure.
	 */
	private void ensureOpen() throws IOException {
		if (writer != null) {
			return;
		}
		try {
			Files.createDirectories(sessionFile.getParent());
			writer = Files.newBufferedWriter(sessionFile,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
			log.info("MCP audit log enabled: {}", sessionFile);
		} catch (IOException e) {
			disableOnce("Could not open MCP audit log file", e);
			throw e;
		}
	}

	/**
	 * Closes the writer on JVM shutdown so the final block is flushed.
	 */
	@PreDestroy
	void close() {
		writeLock.lock();
		try {
			if (writer != null) {
				try {
					writer.flush();
					writer.close();
				} catch (IOException e) {
					log.warn("Error closing MCP audit log: {}", e.getMessage());
				}
			}
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Marks the writer permanently disabled for this session and emits a single
	 * SLF4J error. Subsequent {@link #append} calls become no-ops.
	 */
	private void disableOnce(String message, IOException cause) {
		if (disabledForSession) {
			return;
		}
		disabledForSession = true;
		log.error("{} (audit logging disabled for the rest of this session): {}", message, cause.getMessage());
	}

	/**
	 * Renders one entry as the canonical text block documented in the plan.
	 */
	private static String render(AuditEntry e) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("===== ").append(ENTRY_TS.format(e.instant()))
				.append(" =====================================\n");
		sb.append("tool     : ").append(e.tool()).append('\n');
		sb.append("params   : ").append(e.params() == null || e.params().isBlank() ? "(none)" : e.params()).append('\n');
		sb.append("kind     : ").append(e.kind()).append('\n');
		sb.append("type     : ").append(e.type()).append('\n');
		sb.append("sql      : ").append(e.sql()).append('\n');
		sb.append("outcome  : ").append(e.outcome()).append('\n');
		if ("OK".equals(e.outcome())) {
			sb.append("rows     : ").append(e.rowsOrAffected()).append('\n');
		} else if (e.errorMessage() != null) {
			sb.append("error    : ").append(e.errorMessage()).append('\n');
		}
		sb.append("duration : ").append(e.durationMs()).append(" ms\n");
		sb.append("-------------------------------------------------------------------\n");
		return sb.toString();
	}

	/**
	 * Exposed for tests and operators — the absolute path of the resolved
	 * session file, or {@code null} when audit is disabled.
	 *
	 * @return the session file path or {@code null}
	 */
	Path sessionFile() {
		return sessionFile;
	}

	/**
	 * The 8-character session ID generated at bean creation time.
	 *
	 * <p>Exposed so {@link AuditLogReader} can build the resource response
	 * for {@code oracle://audit/session/{id}} and so clients can reference
	 * the current session.
	 *
	 * @return the session ID (8 hex characters)
	 */
	public String sessionId() {
		return sessionId;
	}
}
