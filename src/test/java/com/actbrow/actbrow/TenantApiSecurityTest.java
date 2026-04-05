package com.actbrow.actbrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.actbrow.actbrow.api.dto.TenantResponse;

/**
 * API key policy for {@code /v1/tenants}: public bootstrap and validate-key only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantApiSecurityTest {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:actbrow-tenantsec;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
		registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
		registry.add("spring.datasource.username", () -> "sa");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.h2.console.enabled", () -> "false");
		registry.add("actbrow.gemini.api-key", () -> "test-key");
		registry.add("actbrow.gemini.base-url", () -> "http://127.0.0.1:9/v1beta");
		registry.add("actbrow.gemini.default-model", () -> "gemini-2.0-flash");
		registry.add("actbrow.gemini.request-timeout", () -> "5s");
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.responseTimeout(Duration.ofSeconds(10))
			.build();
	}

	@Test
	void postTenantsWithoutApiKeyIsAllowed() {
		client().post()
			.uri("/v1/tenants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"sec-test-a","name":"Sec A","enabled":true}
				""")
			.exchange()
			.expectStatus().isOk();
	}

	@Test
	void getTenantsWithoutApiKeyIsRejected() {
		client().get()
			.uri("/v1/tenants")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void tenantAdminPathsDoNotMatchFakePrefix() {
		// Must not hit the public /v1/tenants POST-only bypass (no path "startsWith" hole).
		client().get()
			.uri("/v1/tenants-evil")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void validateKeyWithoutApiKeyIsAllowed() {
		var created = client().post()
			.uri("/v1/tenants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"sec-test-validate","name":"Sec Validate","enabled":true}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(TenantResponse.class)
			.returnResult()
			.getResponseBody();
		assertThat(created).isNotNull();

		client().post()
			.uri("/v1/tenants/validate-key")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"apiKey\":\"" + created.apiKey() + "\"}")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.valid").isEqualTo(true);
	}
}
