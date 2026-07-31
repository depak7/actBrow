package com.actbrow.actbrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.repository.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-auth-matrix;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.flyway.enabled=false",
	"actbrow.claude-proxy.enabled=false"
})
class AuthorizationMatrixTests {

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	private WebTestClient client;
	private String accountKeyA;
	private String accountKeyB;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		accountKeyA = ensureUser("google-a", "a@example.com").getApiKey();
		accountKeyB = ensureUser("google-b", "b@example.com").getApiKey();
	}

	@Test
	void anonymousAssistantCrudIsUnauthorized() {
		client.get().uri("/v1/assistants").exchange().expectStatus().isUnauthorized();
		client.post().uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of(
				"name", "Anon",
				"systemPrompt", "x",
				"model", "gemini-2.5-flash",
				"usePredefinedFlows", false,
				"userId", "forged"))
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void forgedIdentityHeaderDoesNotBypassAuth() {
		String assistantId = createAssistant(accountKeyA, "Owner A");
		client.get().uri("/v1/assistants/" + assistantId + "/activation")
			.header("X-User-Id", userIdFor(accountKeyA))
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void accountCannotAccessAnotherAccountsAssistant() {
		String assistantId = createAssistant(accountKeyA, "Owner A");
		client.get().uri("/v1/assistants/" + assistantId + "/activation")
			.header("Authorization", "Bearer " + accountKeyB)
			.exchange()
			.expectStatus().isNotFound();
	}

	@Test
	void accountCanAccessOwnAssistantResources() {
		String assistantId = createAssistant(accountKeyA, "Owner A");
		client.get().uri("/v1/assistants/" + assistantId + "/activation")
			.header("Authorization", "Bearer " + accountKeyA)
			.exchange()
			.expectStatus().isOk();
	}

	@Test
	void googleLoginWithoutIdTokenFails() {
		client.post().uri("/auth/google")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("googleId", "spoof", "email", "spoof@example.com"))
			.exchange()
			.expectStatus().isBadRequest();
	}

	@Test
	void claudeProxyIsDisabledByDefault() {
		client.post().uri("/claude/v1/chat/completions")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("messages", java.util.List.of()))
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void widgetKeyCannotListAssistants() {
		String assistantId = createAssistant(accountKeyA, "Owner A");
		Map<?, ?> connect = client.get().uri("/v1/assistants/" + assistantId + "/connect")
			.header("Authorization", "Bearer " + accountKeyA)
			.exchange()
			.expectStatus().isOk()
			.expectBody(Map.class)
			.returnResult()
			.getResponseBody();
		assertThat(connect).isNotNull();
		String widgetKey = String.valueOf(connect.get("widgetKey"));
		assertThat(widgetKey).startsWith("wk_");

		client.get().uri("/v1/assistants")
			.header("Authorization", "Bearer " + widgetKey)
			.exchange()
			.expectStatus().isUnauthorized();
	}

	private String createAssistant(String apiKey, String name) {
		Map<?, ?> body = client.post().uri("/v1/assistants")
			.header("Authorization", "Bearer " + apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of(
				"name", name,
				"systemPrompt", "help",
				"model", "gemini-2.5-flash",
				"usePredefinedFlows", false,
				"userId", "ignored-by-server"))
			.exchange()
			.expectStatus().isOk()
			.expectBody(Map.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return String.valueOf(body.get("id"));
	}

	private UserEntity ensureUser(String googleId, String email) {
		return userRepository.findByGoogleId(googleId).orElseGet(() -> {
			UserEntity user = new UserEntity();
			user.setGoogleId(googleId);
			user.setEmail(email);
			user.setFullName(email);
			user.setApiKey("ak_" + UUID.randomUUID().toString().replace("-", ""));
			return userRepository.save(user);
		});
	}

	private String userIdFor(String apiKey) {
		return userRepository.findByApiKey(apiKey).map(UserEntity::getId).orElseThrow();
	}
}
