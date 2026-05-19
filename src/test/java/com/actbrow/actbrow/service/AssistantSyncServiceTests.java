package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.AssistantSyncRequest;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;
import com.actbrow.actbrow.repository.NavigationFlowRepository;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-sync-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"actbrow.public.base-url=http://localhost:8080"
})
class AssistantSyncServiceTests {

	@Autowired
	private AssistantService assistantService;

	@Autowired
	private AssistantSyncService assistantSyncService;

	@Autowired
	private AssistantConnectService assistantConnectService;

	@Autowired
	private ToolService toolService;

	@Autowired
	private KnowledgeDocumentRepository knowledgeDocumentRepository;

	@Autowired
	private NavigationFlowRepository navigationFlowRepository;

	@Autowired
	private AssistantRepository assistantRepository;

	private String assistantId;
	private String setupKey;

	@BeforeEach
	void setUp() {
		knowledgeDocumentRepository.deleteAll();
		navigationFlowRepository.deleteAll();
		assistantRepository.deleteAll();
		var assistant = assistantService.createOrUpdate(new CreateAssistantRequest(
			"Sync Test", "Initial prompt", "gemini-2.5-flash", true, "sync-user"));
		assistantId = assistant.id();
		var entity = assistantRepository.findById(assistantId).orElseThrow();
		assistantService.ensureConnectKeys(entity);
		assistantRepository.save(entity);
		setupKey = entity.getSetupKey();
	}

	@Test
	void syncCreatesNavigationHttpFlowAndKnowledge() {
		var request = new AssistantSyncRequest(
			new AssistantSyncRequest.AssistantConfig("Updated prompt", true),
			java.util.List.of("http://localhost:3000"),
			java.util.List.of(new AssistantSyncRequest.NavigationTool(
				"billing.open", "/settings/billing", "Open Billing", "Go to billing", true)),
			java.util.List.of(new AssistantSyncRequest.HttpTool(
				"orders.list",
				"List Orders",
				"Fetch orders",
				java.util.Map.of("type", "object", "properties", java.util.Map.of()),
				java.util.Map.of("method", "GET", "baseUrl", "https://api.test", "path", "/orders"),
				true)),
			java.util.List.of(new AssistantSyncRequest.FlowConfig(
				"Billing journey",
				"billing|invoice",
				java.util.List.of(new com.actbrow.actbrow.api.dto.NavigationFlowRequest.NavigationStep(
					"navigate", "billing.open", "Open billing")),
				true)),
			java.util.List.of(new AssistantSyncRequest.KnowledgeConfig(
				"Refund policy", "Refunds within 30 days.", null, true)));

		var response = assistantSyncService.sync(assistantId, request);

		assertThat(response.widgetKey()).startsWith("wk_");
		assertThat(response.embedSnippet()).contains("actbrow-sdk.js");
		assertThat(response.summary().get("navigation").created()).isEqualTo(1);
		assertThat(response.summary().get("httpTools").created()).isEqualTo(1);
		assertThat(response.summary().get("flows").created()).isEqualTo(1);
		assertThat(response.summary().get("knowledge").created()).isEqualTo(1);
		assertThat(toolService.findByKey("billing.open")).isPresent();
		assertThat(toolService.findByKey("orders.list")).isPresent();
		assertThat(knowledgeDocumentRepository.findAll()).hasSize(1);
		assertThat(navigationFlowRepository.findAll()).hasSize(1);

		var connect = assistantConnectService.getConnect(assistantId, "sync-user");
		assertThat(connect.setupPrompt()).contains(setupKey);
		assertThat(connect.lastSyncedAt()).isNotNull();
	}
}
