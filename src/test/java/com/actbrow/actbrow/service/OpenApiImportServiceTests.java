package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.api.dto.ApiIntegrationResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.api.dto.ImportApiSpecRequest;
import com.actbrow.actbrow.api.dto.ImportApiSpecResponse;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.ApiIntegrationRepository;
import com.actbrow.actbrow.repository.AssistantRepository;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-openapi-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"actbrow.public.base-url=http://localhost:8080"
})
class OpenApiImportServiceTests {

	private static final String SPEC = """
		{
		  "openapi": "3.0.0",
		  "info": {"title": "Petstore", "version": "1.0.0"},
		  "servers": [{"url": "https://api.petstore.test/v1"}],
		  "paths": {
		    "/pets/{petId}": {
		      "get": {
		        "operationId": "getPetById",
		        "summary": "Find pet by id",
		        "parameters": [
		          {"name": "petId", "in": "path", "required": true, "schema": {"type": "integer"}},
		          {"name": "verbose", "in": "query", "required": false, "schema": {"type": "boolean"}}
		        ]
		      }
		    },
		    "/pets": {
		      "post": {
		        "operationId": "createPet",
		        "summary": "Create a pet",
		        "requestBody": {
		          "content": {
		            "application/json": {
		              "schema": {
		                "type": "object",
		                "required": ["name"],
		                "properties": {
		                  "name": {"type": "string"},
		                  "tag": {"type": "string"}
		                }
		              }
		            }
		          }
		        }
		      }
		    }
		  }
		}
		""";

	@Autowired
	private OpenApiImportService openApiImportService;

	@Autowired
	private ToolService toolService;

	@Autowired
	private AssistantService assistantService;

	@Autowired
	private AssistantRepository assistantRepository;

	@Autowired
	private ApiIntegrationRepository apiIntegrationRepository;

	private String assistantId;

	@BeforeEach
	void setUp() {
		apiIntegrationRepository.deleteAll();
		assistantRepository.deleteAll();
		var assistant = assistantService.createOrUpdate(new CreateAssistantRequest(
			"OpenAPI Test", "Initial prompt", "gemini-2.5-flash", true, "openapi-user"));
		assistantId = assistant.id();
	}

	@Test
	@SuppressWarnings("unchecked")
	void importGeneratesAttachesAndShapesBrowserHttpTools() {
		ImportApiSpecResponse response = openApiImportService.importSpec(assistantId,
			new ImportApiSpecRequest("petstore", SPEC, null, null));

		assertThat(response.created()).isEqualTo(2);
		assertThat(response.updated()).isZero();
		// Keys are prefixed with a per-assistant discriminator, so assert on the readable suffix.
		assertThat(response.toolKeys()).hasSize(2);
		assertThat(response.toolKeys()).anySatisfy(k -> assertThat(k).endsWith("petstore.getpetbyid"));
		assertThat(response.toolKeys()).anySatisfy(k -> assertThat(k).endsWith("petstore.createpet"));

		List<ToolResponse> attached = toolService.listAssistantTools(assistantId);
		assertThat(attached).extracting(ToolResponse::key)
			.anyMatch(k -> k.endsWith("petstore.getpetbyid"));

		ToolResponse getPet = attached.stream().filter(t -> t.key().endsWith("petstore.getpetbyid")).findFirst()
			.orElseThrow();
		assertThat(getPet.type()).isEqualTo(ToolType.SERVER_HTTP);
		Map<String, Object> meta = getPet.metadata();
		assertThat(meta).containsEntry("method", "GET");
		assertThat(meta).containsEntry("path", "/pets/{petId}");
		assertThat(meta).containsEntry("execution", "browser");
		assertThat(meta).containsEntry("credentials", "include");
		assertThat(meta).containsEntry("baseUrl", "https://api.petstore.test/v1");

		List<Map<String, Object>> params = (List<Map<String, Object>>) meta.get("parameters");
		assertThat(params).anySatisfy(p -> {
			assertThat(p).containsEntry("name", "petId").containsEntry("in", "path").containsEntry("required", true);
		});
		assertThat(params).anySatisfy(p -> {
			assertThat(p).containsEntry("name", "verbose").containsEntry("in", "query");
		});

		Map<String, Object> inputProps = (Map<String, Object>) getPet.inputSchema().get("properties");
		assertThat(inputProps).containsKeys("petId", "verbose");
		assertThat((List<String>) getPet.inputSchema().get("required")).contains("petId");

		ToolResponse createPet = attached.stream().filter(t -> t.key().endsWith("petstore.createpet")).findFirst()
			.orElseThrow();
		List<Map<String, Object>> createParams = (List<Map<String, Object>>) createPet.metadata().get("parameters");
		assertThat(createParams).isEmpty();
		Map<String, Object> createProps = (Map<String, Object>) createPet.inputSchema().get("properties");
		assertThat(createProps).containsKeys("name", "tag");
		assertThat((List<String>) createPet.inputSchema().get("required")).contains("name");
	}

	@Test
	void reimportUpdatesInsteadOfDuplicating() {
		openApiImportService.importSpec(assistantId, new ImportApiSpecRequest("petstore", SPEC, null, null));
		ImportApiSpecResponse second = openApiImportService.importSpec(assistantId,
			new ImportApiSpecRequest("petstore", SPEC, null, null));

		assertThat(second.created()).isZero();
		assertThat(second.updated()).isEqualTo(2);

		List<ApiIntegrationResponse> integrations = openApiImportService.listIntegrations(assistantId);
		assertThat(integrations).hasSize(1);
		assertThat(integrations.get(0).baseUrl()).isEqualTo("https://api.petstore.test/v1");
	}

	@Test
	void deleteRejectsForeignAssistantAndRemovesOwnTools() {
		ImportApiSpecResponse response = openApiImportService.importSpec(assistantId,
			new ImportApiSpecRequest("petstore", SPEC, null, null));
		String integrationId = response.integrationId();

		// A different assistant must not be able to delete this integration.
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> openApiImportService.deleteIntegration("someone-else", integrationId))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(toolService.listAssistantTools(assistantId)).isNotEmpty();

		// Owner can delete; tools are removed.
		openApiImportService.deleteIntegration(assistantId, integrationId);
		assertThat(toolService.listAssistantTools(assistantId)).isEmpty();
		assertThat(openApiImportService.listIntegrations(assistantId)).isEmpty();
	}

	@Test
	void baseUrlOverrideTakesPrecedence() {
		ImportApiSpecResponse response = openApiImportService.importSpec(assistantId,
			new ImportApiSpecRequest("petstore", SPEC, "https://staging.petstore.test/", null));

		ToolResponse anyTool = toolService.findByKey(response.toolKeys().get(0))
			.map(e -> toolService.listAssistantTools(assistantId).stream()
				.filter(t -> t.key().equals(e.getKey())).findFirst().orElseThrow())
			.orElseThrow();
		assertThat(anyTool.metadata()).containsEntry("baseUrl", "https://staging.petstore.test");
	}
}
