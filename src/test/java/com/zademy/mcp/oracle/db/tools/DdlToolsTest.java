package com.zademy.mcp.oracle.db.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlToolsTest {

	@Test
	@DisplayName("exposes only COMMENT tools")
	void exposesOnlyCommentTools() throws NoSuchMethodException {
		assertThat(method("commentOnTable", String.class, String.class, String.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNotNull();
		assertThat(method("commentOnColumn", String.class, String.class, String.class, String.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNotNull();

		assertThat(method("createOrReplaceView", String.class, String.class, String.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNull();
		assertThat(method("createSynonym", String.class, String.class, String.class, String.class, Boolean.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNull();
		assertThat(method("createSequence", String.class, String.class, Long.class, Long.class, Long.class,
				Boolean.class, Boolean.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNull();
		assertThat(method("createIndex", String.class, String.class, String.class, String.class, List.class,
				Boolean.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNull();
		assertThat(method("createMviewLog", String.class, String.class, Boolean.class, Boolean.class, Boolean.class))
				.extracting(method -> method.getAnnotation(McpTool.class))
				.isNull();
	}

	private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
		return DdlTools.class.getMethod(name, parameterTypes);
	}
}
