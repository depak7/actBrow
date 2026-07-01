package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.NavigationFlowEntity;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.AssistantToolBindingRepository;
import com.actbrow.actbrow.repository.NavigationFlowRepository;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-delete-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class AssistantDeleteTests {

	@Autowired
	private AssistantService assistantService;
	@Autowired
	private AssistantRepository assistantRepository;
	@Autowired
	private AssistantToolBindingRepository toolBindingRepository;
	@Autowired
	private NavigationFlowRepository navigationFlowRepository;

	@Test
	void deleteRemovesAssistantAndItsDependents() {
		AssistantResponse created = assistantService.createOrUpdate(
			new CreateAssistantRequest("Temp Assistant", "be helpful", null, true, "user-1"));
		String id = created.id();

		// createOrUpdate attaches built-in tools, so bindings exist to clean up
		assertThat(toolBindingRepository.findAllByAssistantId(id)).isNotEmpty();

		// navigation_flows has a real FK to assistants — would block deletion if not removed first
		NavigationFlowEntity flow = new NavigationFlowEntity();
		flow.setAssistant(assistantService.requireEntity(id));
		flow.setName("checkout");
		flow.setTriggerPhrase("go to checkout");
		flow.setStepsJson("[]");
		flow.setEnabled(true);
		navigationFlowRepository.save(flow);

		assistantService.delete(id);

		assertThat(assistantRepository.existsById(id)).isFalse();
		assertThat(toolBindingRepository.findAllByAssistantId(id)).isEmpty();
		assertThat(navigationFlowRepository.findAllByAssistantIdOrderByCreatedAt(id)).isEmpty();
	}

	@Test
	void deletingUnknownAssistantIsNoOp() {
		assertThatCode(() -> assistantService.delete("does-not-exist")).doesNotThrowAnyException();
	}
}
