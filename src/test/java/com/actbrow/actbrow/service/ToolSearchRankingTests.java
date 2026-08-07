package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code tool.search} is how the agent discovers anything outside the 25 schemas it can see at once,
 * so its recall determines whether a large catalog is usable at all. It previously required the whole
 * query phrase to appear verbatim in the key or description, which silently made capabilities
 * undiscoverable as the catalog grew — the agent would report it could not do something it could.
 */
class ToolSearchRankingTests {

	@Mock
	private RunMemoryService runMemoryService;

	private ProgressiveToolDisclosureService service;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		when(runMemoryService.getSnapshot(anyString()))
			.thenReturn(new RunMemoryService.RunMemorySnapshot("", "", "", Map.of(), Map.of(), List.of(),
				null, Map.of()));
		service = new ProgressiveToolDisclosureService(runMemoryService, objectMapper, 25, 40);
	}

	private ToolDescriptor tool(String key, String description) {
		return new ToolDescriptor("id-" + key, key, description, "{\"type\":\"object\"}",
			ToolType.SERVER_HTTP, key, Map.of(), Map.of());
	}

	@SuppressWarnings("unchecked")
	private List<String> searchKeys(List<ToolDescriptor> catalog, String query) {
		RunEntity run = new RunEntity();
		run.setId("search-ranking-run");
		ToolExecutionResult result = service.execute(run, catalog,
			ProgressiveToolDisclosureService.TOOL_SEARCH, Map.of("query", query));
		assertThat(result.success()).isTrue();
		try {
			Map<String, Object> body = objectMapper.readValue(result.structuredOutput(), Map.class);
			Object tools = body.get("tools");
			if (!(tools instanceof List<?> cards)) {
				throw new AssertionError("search body missing tools array: " + result.structuredOutput());
			}
			List<String> keys = new java.util.ArrayList<>();
			for (Object card : cards) {
				if (card instanceof Map<?, ?> map) {
					keys.add(String.valueOf(map.get("key")));
				}
			}
			return keys;
		}
		catch (AssertionError e) {
			throw e;
		}
		catch (Exception e) {
			throw new AssertionError("search output was not parseable JSON", e);
		}
	}

	@Test
	void multiWordQueryFindsToolsThatDoNotContainThePhraseVerbatim() {
		List<ToolDescriptor> catalog = List.of(
			tool("orders.refund.create", "Issue a refund against an existing order"),
			tool("billing.invoice.list", "List invoices for the current account"),
			tool("users.profile.get", "Fetch a user profile"));

		// No tool contains the literal string "refund a customer order" anywhere.
		List<String> keys = searchKeys(catalog, "refund a customer order");

		assertThat(keys).isNotEmpty();
		assertThat(keys.get(0)).isEqualTo("orders.refund.create");
	}

	@Test
	void dottedKeysAreMatchedTermByTerm() {
		List<ToolDescriptor> catalog = List.of(
			tool("support.ticket.escalate", "Escalate a support ticket to a human agent"),
			tool("orders.refund.create", "Issue a refund"));

		assertThat(searchKeys(catalog, "escalate ticket")).first().isEqualTo("support.ticket.escalate");
	}

	@Test
	void keyMatchesOutrankDescriptionMatches() {
		List<ToolDescriptor> catalog = List.of(
			// Mentions "refund" only in prose.
			tool("billing.note.add", "Add a note, for example about a refund"),
			tool("orders.refund.create", "Issue money back"));

		assertThat(searchKeys(catalog, "refund")).first().isEqualTo("orders.refund.create");
	}

	@Test
	void pluralAndSingularStillMatch() {
		List<ToolDescriptor> catalog = List.of(
			tool("order.cancel", "Cancel an order"),
			tool("users.profile.get", "Fetch a user profile"));

		assertThat(searchKeys(catalog, "cancel orders")).first().isEqualTo("order.cancel");
	}

	@Test
	void irrelevantToolsAreExcludedRatherThanRankedLast() {
		List<ToolDescriptor> catalog = List.of(
			tool("orders.refund.create", "Issue a refund"),
			tool("weather.forecast.get", "Get the weather forecast"));

		assertThat(searchKeys(catalog, "refund")).containsExactly("orders.refund.create");
	}

	@Test
	void remainsUsableAcrossALargeCatalog() {
		List<ToolDescriptor> catalog = new java.util.ArrayList<>();
		for (int i = 0; i < 200; i++) {
			catalog.add(tool("noise.module" + i + ".action", "Does something unrelated number " + i));
		}
		catalog.add(tool("orders.refund.create", "Issue a refund against an existing order"));

		// The needle is last in catalog order, so only ranking can surface it.
		assertThat(searchKeys(catalog, "refund an order")).first().isEqualTo("orders.refund.create");
	}

	@Test
	void blankQueryStillBrowsesTheCatalog() {
		List<ToolDescriptor> catalog = List.of(
			tool("orders.refund.create", "Issue a refund"),
			tool("users.profile.get", "Fetch a user profile"));

		// An empty query is a browse, not a filter — it must not return nothing.
		assertThat(searchKeys(catalog, "")).hasSize(2);
	}
}
