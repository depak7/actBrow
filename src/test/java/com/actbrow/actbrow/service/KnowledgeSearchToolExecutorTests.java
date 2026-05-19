package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.KnowledgeDocumentRequest;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-knowledge-search-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class KnowledgeSearchToolExecutorTests {

	@Autowired
	private KnowledgeSearchToolExecutor knowledgeSearchToolExecutor;

	@Autowired
	private KnowledgeService knowledgeService;

	@Autowired
	private KnowledgeDocumentRepository knowledgeDocumentRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		knowledgeDocumentRepository.deleteAll();
	}

	@Test
	void returnsMatchingDocuments() throws Exception {
		knowledgeService.create("assistant-1", new KnowledgeDocumentRequest(
			"Refund policy", "Refunds are allowed within 30 days.", null, true));

		var result = knowledgeSearchToolExecutor.execute("assistant-1", java.util.Map.of("query", "refund policy"));

		assertThat(result.success()).isTrue();
		JsonNode payload = objectMapper.readTree(result.structuredOutput());
		assertThat(payload.get("count").asInt()).isEqualTo(1);
		assertThat(payload.get("results").get(0).get("title").asText()).isEqualTo("Refund policy");
	}

	@Test
	void returnsEmptyResultWhenNothingMatches() throws Exception {
		var result = knowledgeSearchToolExecutor.execute("assistant-1", java.util.Map.of("query", "unknown topic"));

		assertThat(result.success()).isTrue();
		JsonNode payload = objectMapper.readTree(result.structuredOutput());
		assertThat(payload.get("count").asInt()).isZero();
		assertThat(payload.get("message").asText()).contains("No matching knowledge documents");
	}

	@Test
	void requiresQuery() {
		var result = knowledgeSearchToolExecutor.execute("assistant-1", java.util.Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.error()).contains("query is required");
	}
}
