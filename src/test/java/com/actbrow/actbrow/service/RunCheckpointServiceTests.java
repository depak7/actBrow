package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.model.RunCheckpointEntity;
import com.actbrow.actbrow.model.RunPhase;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-checkpoint-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class RunCheckpointServiceTests {

	@Autowired
	private RunCheckpointService service;

	@Test
	void recordsAndUpsertsSingleRowPerRun() {
		service.recordPhase("run-1", "conv-1", RunPhase.PLANNING, 0);
		service.record("run-1", "conv-1", RunPhase.EXECUTING, 1, "{\"plan\":1}", "{\"exec\":1}", null);

		RunCheckpointEntity checkpoint = service.find("run-1").orElseThrow();
		assertThat(checkpoint.getPhase()).isEqualTo(RunPhase.EXECUTING);
		assertThat(checkpoint.getStepIndex()).isEqualTo(1);
		assertThat(checkpoint.getPlannerOutcomeJson()).isEqualTo("{\"plan\":1}");
		assertThat(checkpoint.getLastExecutionJson()).isEqualTo("{\"exec\":1}");
	}

	@Test
	void clearRemovesCheckpoint() {
		service.recordPhase("run-2", "conv-2", RunPhase.VERIFYING, 3);
		assertThat(service.find("run-2")).isPresent();
		service.clear("run-2");
		assertThat(service.find("run-2")).isEmpty();
	}

	@Test
	void clearingUnknownRunIsNoOp() {
		service.clear("does-not-exist");
		assertThat(service.find("does-not-exist")).isEmpty();
	}
}
