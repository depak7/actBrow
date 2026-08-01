package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.repository.AssistantRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * The auth filter resolves an API key on every request, so this cache is the highest-traffic one in
 * the system — and the most security-sensitive, because entries are keyed by the secret itself.
 */
@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=test-model",
	"spring.datasource.url=jdbc:h2:mem:actbrow-apikey-cache;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.properties.hibernate.generate_statistics=true"
})
class ApiKeyIdentityResolverTests {

	@Autowired
	private ApiKeyIdentityResolver resolver;
	@Autowired
	private AssistantService assistantService;
	@Autowired
	private AssistantRepository assistantRepository;
	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	private AssistantDefinitionEntity newAssistant(String name, String user) {
		AssistantResponse created = assistantService.createOrUpdate(
			new CreateAssistantRequest(name, "be helpful", null, false, user));
		return assistantRepository.findById(created.id()).orElseThrow();
	}

	@Test
	void repeatedWidgetKeyLookupsHitTheDatabaseOnce() {
		AssistantDefinitionEntity assistant = newAssistant("Cache A", "cache-user-a");
		String widgetKey = assistant.getWidgetKey();
		assertThat(widgetKey).isNotBlank();
		resolver.evictAll();

		Statistics stats = statistics();
		stats.setStatisticsEnabled(true);
		stats.clear();

		Optional<ApiKeyIdentityResolver.Identity> first = resolver.resolveWidgetKey(widgetKey);
		long afterFirst = stats.getPrepareStatementCount();
		for (int i = 0; i < 20; i++) {
			resolver.resolveWidgetKey(widgetKey);
		}
		long afterMany = stats.getPrepareStatementCount();

		assertThat(first).isPresent();
		assertThat(first.get().assistantId()).isEqualTo(assistant.getId());
		assertThat(first.get().authType()).isEqualTo("widget");
		// Widget identity carries no account id — a cached entry must not widen privileges.
		assertThat(first.get().userId()).isNull();
		assertThat(afterMany)
			.as("20 further lookups must not touch the database")
			.isEqualTo(afterFirst);
	}

	@Test
	void unknownKeysAreNotCached() {
		resolver.evictAll();
		Statistics stats = statistics();
		stats.setStatisticsEnabled(true);
		stats.clear();

		assertThat(resolver.resolveWidgetKey("wk_does_not_exist")).isEmpty();
		long afterFirst = stats.getPrepareStatementCount();
		assertThat(resolver.resolveWidgetKey("wk_does_not_exist")).isEmpty();
		long afterSecond = stats.getPrepareStatementCount();

		// Caching a miss would mean a freshly created assistant's key returns 401 until the entry
		// expires — indistinguishable from a broken install during onboarding.
		assertThat(afterSecond)
			.as("a miss must re-query so a newly created key works immediately")
			.isGreaterThan(afterFirst);
	}

	@Test
	void evictAllMakesRevocationImmediate() {
		AssistantDefinitionEntity assistant = newAssistant("Cache B", "cache-user-b");
		String widgetKey = assistant.getWidgetKey();
		assertThat(resolver.resolveWidgetKey(widgetKey)).isPresent();

		assistantRepository.delete(assistant);
		resolver.evictAll();

		assertThat(resolver.resolveWidgetKey(widgetKey))
			.as("after eviction the deleted assistant's key must stop resolving")
			.isEmpty();
	}

	@Test
	void separateKeyKindsDoNotCollide() {
		AssistantDefinitionEntity assistant = newAssistant("Cache C", "cache-user-c");
		resolver.evictAll();

		// Same string resolved through two different key kinds must not share a cache entry.
		assertThat(resolver.resolveWidgetKey(assistant.getWidgetKey())).isPresent();
		assertThat(resolver.resolveSetupKey(assistant.getWidgetKey()))
			.as("a widget key must never resolve as a setup key via a shared cache entry")
			.isEmpty();
	}
}
