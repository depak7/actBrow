package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.repository.ToolRepository;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-builtin-tools-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class BuiltinToolCatalogTests {

	@Autowired
	private ToolRepository toolRepository;

	@Autowired
	private ToolService toolService;

	@Autowired
	private AssistantService assistantService;

	@Test
	void seedsAndAttachesKnowledgeSearchTool() {
		assertThat(toolRepository.findByKey("knowledge.search")).isPresent();

		var assistant = assistantService.createOrUpdate(new CreateAssistantRequest(
			"Support", "Handle support", "gemini-2.5-flash", false, "builtin-tool-user"));
		var tools = toolService.listDescriptorsForAssistant(assistant.id());

		assertThat(tools.stream().map(tool -> tool.key())).contains("knowledge.search");
	}
}
