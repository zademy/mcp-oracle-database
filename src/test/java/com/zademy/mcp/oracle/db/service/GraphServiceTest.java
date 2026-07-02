package com.zademy.mcp.oracle.db.service;

import com.zademy.mcp.oracle.db.model.SchemaGraph;
import com.zademy.mcp.oracle.db.persistence.OracleDataAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphService}: Mermaid generation and edge building
 * for the FK and dependency graphs. {@link OracleDataAccess} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

	@Mock
	private OracleDataAccess db;

	private GraphService service;

	@BeforeEach
	void setUp() {
		service = new GraphService(db);
	}

	private static Map<String, Object> fkRow(String child, String fk, String parentOwner, String parent) {
		Map<String, Object> r = new HashMap<>();
		r.put("child_table", child);
		r.put("fk_name", fk);
		r.put("parent_owner", parentOwner);
		r.put("parent_table", parent);
		return r;
	}

	private static Map<String, Object> depRow(String name, String type, String refName, String refType) {
		Map<String, Object> r = new HashMap<>();
		r.put("name", name);
		r.put("type", type);
		r.put("referenced_name", refName);
		r.put("referenced_type", refType);
		return r;
	}

	@Test
	@DisplayName("FK graph: empty result produces placeholder Mermaid and no edges")
	void fkGraph_empty() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of());

		SchemaGraph graph = service.fkGraph("HR", null);

		assertThat(graph.edges()).isEmpty();
		assertThat(graph.graphType()).isEqualTo("FK");
		assertThat(graph.mermaid()).contains("no foreign keys found");
		assertThat(graph.focus()).isNull();
	}

	@Test
	@DisplayName("FK graph: edges and Mermaid reflect child→parent relationships")
	void fkGraph_withEdges() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of(
				fkRow("ORDER_ITEMS", "FK_ORD_ITEMS_ORD", "HR", "ORDERS"),
				fkRow("ORDER_ITEMS", "FK_ORD_ITEMS_PROD", "HR", "PRODUCTS"),
				fkRow("ORDERS", "FK_ORD_CUST", "HR", "CUSTOMERS")));

		SchemaGraph graph = service.fkGraph("HR", null);

		assertThat(graph.edges()).hasSize(3);
		assertThat(graph.edges().get(0).fromObject()).isEqualTo("HR.ORDER_ITEMS");
		assertThat(graph.edges().get(0).toObject()).isEqualTo("HR.ORDERS");
		assertThat(graph.edges().get(0).edgeType()).isEqualTo("FK");
		assertThat(graph.mermaid()).startsWith("graph LR");
		assertThat(graph.mermaid()).contains("ORDER_ITEMS");
		assertThat(graph.mermaid()).contains("ORDERS");
		assertThat(graph.mermaid()).contains("PRODUCTS");
		assertThat(graph.mermaid()).contains("CUSTOMERS");
	}

	@Test
	@DisplayName("FK graph: duplicate child→parent pairs are de-duplicated in Mermaid")
	void fkGraph_deduplicates() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of(
				fkRow("CHILD", "FK_1", "HR", "PARENT"),
				fkRow("CHILD", "FK_2", "HR", "PARENT")));

		SchemaGraph graph = service.fkGraph("HR", null);

		assertThat(graph.edges()).hasSize(2);
		long arrowCount = graph.mermaid().lines().filter(l -> l.contains("-->")).count();
		assertThat(arrowCount).isEqualTo(1);
	}

	@Test
	@DisplayName("Dependency graph: self-references are filtered out")
	void dependencyGraph_filtersSelfRefs() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of(
				depRow("MY_VIEW", "VIEW", "MY_VIEW", "VIEW"),
				depRow("MY_VIEW", "VIEW", "BASE_TABLE", "TABLE")));

		SchemaGraph graph = service.dependencyGraph("HR", null, null);

		assertThat(graph.edges()).hasSize(1);
		assertThat(graph.edges().get(0).fromObject()).isEqualTo("HR.MY_VIEW");
		assertThat(graph.edges().get(0).toObject()).isEqualTo("HR.BASE_TABLE");
		assertThat(graph.edges().get(0).edgeType()).isEqualTo("DEPENDS_ON");
	}

	@Test
	@DisplayName("Dependency graph: empty result produces placeholder Mermaid")
	void dependencyGraph_empty() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of());

		SchemaGraph graph = service.dependencyGraph("HR", null, null);

		assertThat(graph.edges()).isEmpty();
		assertThat(graph.graphType()).isEqualTo("DEPENDENCY");
		assertThat(graph.mermaid()).contains("no dependencies found");
	}

	@Test
	@DisplayName("Dependency graph: Mermaid uses simple arrow without label")
	void dependencyGraph_mermaidFormat() {
		when(db.queryForList(any(String.class), anyMap())).thenReturn(List.of(
				depRow("EMP_VIEW", "VIEW", "EMPLOYEES", "TABLE")));

		SchemaGraph graph = service.dependencyGraph("HR", null, null);

		assertThat(graph.mermaid()).startsWith("graph LR");
		assertThat(graph.mermaid()).contains("EMP_VIEW --> EMPLOYEES");
	}
}
