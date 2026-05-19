package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.KnowledgeDocumentRequest;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-knowledge-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class KnowledgeServiceTests {

	@Autowired
	private KnowledgeService knowledgeService;

	@Autowired
	private KnowledgeDocumentRepository knowledgeDocumentRepository;

	@BeforeEach
	void clean() {
		knowledgeDocumentRepository.deleteAll();
	}

	@Test
	void findRelevantMatchesQueryTerms() {
		knowledgeService.create("assistant-1", new KnowledgeDocumentRequest(
			"Refund policy", "Refunds are allowed within 30 days for annual plans.", "Support SOP", true));
		knowledgeService.create("assistant-1", new KnowledgeDocumentRequest(
			"Shipping", "Orders ship within two business days.", null, true));

		var results = knowledgeService.findRelevant("assistant-1", "annual refund policy", null, 5);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().title()).isEqualTo("Refund policy");
	}

	@Test
	void findRelevantBoostsDocumentsMatchingPath() {
		knowledgeService.create("assistant-1", new KnowledgeDocumentRequest(
			"Billing FAQ", "Billing page explains proration for /settings/billing upgrades.", null, true));
		knowledgeService.create("assistant-1", new KnowledgeDocumentRequest(
			"General support", "Contact support@example.com for help.", null, true));

		var results = knowledgeService.findRelevant("assistant-1", "billing proration", "/settings/billing", 5);

		assertThat(results).isNotEmpty();
		assertThat(results.getFirst().title()).isEqualTo("Billing FAQ");
	}
}
