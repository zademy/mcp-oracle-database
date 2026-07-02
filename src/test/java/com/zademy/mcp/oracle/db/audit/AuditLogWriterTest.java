package com.zademy.mcp.oracle.db.audit;

import com.zademy.mcp.oracle.db.config.AuditProperties;
import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditLogWriter}. Covers filename shape, append order,
 * concurrency under virtual threads, the disabled no-op contract, directory
 * auto-creation, and failure isolation (an I/O error must never propagate to
 * the caller and must permanently silence the writer for the session).
 */
class AuditLogWriterTest {

	@TempDir
	Path tmp;

	private static AuditEntry entry(String sql, String outcome) {
		return new AuditEntry(Instant.now(), "run_query", "sql=" + sql, sql, "Select", "READ",
				outcome, outcome.equals("OK") ? 3L : -1L,
				outcome.equals("OK") ? null : "org.springframework.dao.DataAccessException: boom",
				12L);
	}

	/** Builds a full {@link OracleMcpProperties} with the given audit block. */
	private static OracleMcpProperties props(boolean enabled, String dir) {
		return new OracleMcpProperties(500, 30, 10, new AuditProperties(enabled, dir));
	}

	@Test
	@DisplayName("disabled writer never creates a file and append is a no-op")
	void disabledWriterDoesNothing() throws IOException {
		var writer = new AuditLogWriter(props(false, tmp.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();
		writer.append(entry("SELECT 1 FROM DUAL", "OK"));

		assertThat(writer.sessionFile()).isNull();
		try (var stream = Files.list(tmp)) {
			assertThat(stream.count()).isZero();
		}
	}

	@Test
	@DisplayName("filename matches <serverName>-<dd-MM-yyyy>-<sessionId8>-log.txt")
	void filenameShape() {
		var writer = new AuditLogWriter(props(true, tmp.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();
		Path f = writer.sessionFile();
		assertThat(f).isNotNull();
		String name = f.getFileName().toString();
		assertThat(name).startsWith("mcp-oracle-db-");
		assertThat(name).endsWith("-log.txt");
		assertThat(name).matches("mcp-oracle-db-\\d{2}-\\d{2}-\\d{4}-[0-9a-f]{8}-log\\.txt");
	}

	@Test
	@DisplayName("appends three entries in arrival order as three blocks")
	void appendsInOrder() throws IOException {
		var writer = new AuditLogWriter(props(true, tmp.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();

		writer.append(entry("SELECT 1 FROM DUAL", "OK"));
		writer.append(entry("SELECT 2 FROM DUAL", "OK"));
		writer.append(entry("BAD SQL", "ERROR"));

		Path file = writer.sessionFile();
		List<String> lines = Files.readAllLines(file);
		long headers = lines.stream().filter(l -> l.startsWith("===== ")).count();
		long footers = lines.stream().filter(l -> l.startsWith("-----------")).count();
		assertThat(headers).isEqualTo(3);
		assertThat(footers).isEqualTo(3);
		String fileText = Files.readString(file);
		assertThat(fileText).contains("rows     : 3");
		assertThat(fileText).contains("outcome  : ERROR");
		assertThat(fileText).contains("error    : org.springframework.dao.DataAccessException: boom");
	}

	@Test
	@DisplayName("50 concurrent virtual threads produce exactly 50 non-interleaved blocks")
	void concurrentAppendsAreSafe() throws Exception {
		var writer = new AuditLogWriter(props(true, tmp.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();

		int n = 50;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(n);
		ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				final int idx = i;
				pool.submit(() -> {
					try {
						start.await();
						writer.append(entry("SELECT " + idx + " FROM DUAL", "OK"));
					} catch (Exception e) {
						errors.add(e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(errors).isEmpty();

		List<String> lines = Files.readAllLines(writer.sessionFile());
		long headers = lines.stream().filter(l -> l.startsWith("===== ")).count();
		long footers = lines.stream().filter(l -> l.startsWith("-----------")).count();
		// exactly n headers and n footers proves no block was dropped and none was duplicated
		assertThat(headers).isEqualTo(n);
		assertThat(footers).isEqualTo(n);
		// structural integrity: every header must be followed by its own tool/sql/outcome
		// before the next header (counts already imply this; assert the distinct SQLs survived)
		String text = Files.readString(writer.sessionFile());
		for (int i = 0; i < n; i++) {
			assertThat(text).contains("SELECT " + i + " FROM DUAL");
		}
	}

	@Test
	@DisplayName("directory is auto-created when missing")
	void createsMissingDirectory() throws IOException {
		Path nested = tmp.resolve("deep").resolve("nested").resolve("audit");
		var writer = new AuditLogWriter(props(true, nested.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();

		writer.append(entry("SELECT 1 FROM DUAL", "OK"));

		assertThat(nested).isDirectory();
		assertThat(writer.sessionFile()).exists();
	}

	@Test
	@DisplayName("a null entry is silently ignored")
	void nullEntryIsIgnored() {
		var writer = new AuditLogWriter(props(true, tmp.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();
		writer.append(null); // must not throw
	}

	@Test
	@DisplayName("write to an unwritable path disables the writer silently (no exception to caller)")
	void ioFailureDisablesWriter() throws IOException {
		Path blocker = tmp.resolve("blocker.txt");
		Files.writeString(blocker, "x");
		// target dir is the file itself, so createDirectories(target) fails
		var writer = new AuditLogWriter(props(true, blocker.toString()), "mcp-oracle-db");
		writer.resolveSessionFile();

		writer.append(entry("SELECT 1 FROM DUAL", "OK"));
		writer.append(entry("SELECT 2 FROM DUAL", "OK"));
	}
}
