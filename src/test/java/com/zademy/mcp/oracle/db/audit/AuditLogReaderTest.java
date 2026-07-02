package com.zademy.mcp.oracle.db.audit;

import com.zademy.mcp.oracle.db.config.AuditProperties;
import com.zademy.mcp.oracle.db.config.OracleMcpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditLogReaderTest {

	@TempDir
	Path tempDir;

	private AuditLogWriter writer;

	@BeforeEach
	void setUp() {
		writer = mock(AuditLogWriter.class);
	}

	@Test
	void currentSessionIdDelegatesToWriter() {
		when(writer.sessionId()).thenReturn("aabbccdd");
		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(false, "./logs"));
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.currentSessionId()).isEqualTo("aabbccdd");
	}

	@Test
	void readSessionReturnsContentWhenEnabled() throws IOException {
		Files.writeString(tempDir.resolve("mcp-oracle-db-28-06-2026-a1b2c3d4-log.txt"),
				"===== entry 1 =====\nSQL: SELECT 1\n");

		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(true, tempDir.toString()));
		var reader = new AuditLogReader(props, writer);

		Optional<String> content = reader.readSession("a1b2c3d4");

		assertThat(content).isPresent();
		assertThat(content.get()).contains("SELECT 1");
	}

	@Test
	void readSessionReturnsEmptyWhenDisabled() {
		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(false, tempDir.toString()));
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.readSession("a1b2c3d4")).isEmpty();
	}

	@Test
	void readSessionReturnsEmptyWhenFileNotFound() throws IOException {
		Files.writeString(tempDir.resolve("mcp-oracle-db-28-06-2026-other123-log.txt"), "entry");

		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(true, tempDir.toString()));
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.readSession("a1b2c3d4")).isEmpty();
	}

	@Test
	void listSessionIdsExtractsFromFilenames() throws IOException {
		Files.writeString(tempDir.resolve("mcp-oracle-db-27-06-2026-aaaa1111-log.txt"), "a");
		Files.writeString(tempDir.resolve("mcp-oracle-db-28-06-2026-bbbb2222-log.txt"), "b");
		Files.createFile(tempDir.resolve("not-a-log.txt"));

		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(true, tempDir.toString()));
		var reader = new AuditLogReader(props, writer);

		List<String> ids = reader.listSessionIds();

		assertThat(ids).containsExactly("aaaa1111", "bbbb2222");
	}

	@Test
	void listSessionIdsEmptyWhenDisabled() {
		var props = new OracleMcpProperties(100, 30, 10, new AuditProperties(false, tempDir.toString()));
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.listSessionIds()).isEmpty();
	}

	@Test
	void listSessionIdsEmptyWhenDirMissing() {
		var props = new OracleMcpProperties(100, 30, 10,
				new AuditProperties(true, tempDir.resolve("nonexistent").toString()));
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.listSessionIds()).isEmpty();
	}

	@Test
	void readSessionHandlesNullAuditProperties() {
		var props = new OracleMcpProperties(100, 30, 10, null);
		var reader = new AuditLogReader(props, writer);

		assertThat(reader.readSession("anything")).isEmpty();
		assertThat(reader.listSessionIds()).isEmpty();
	}
}
