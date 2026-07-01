package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunTraceEntity;

@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=gemini-2.5-flash",
	"spring.datasource.url=jdbc:h2:mem:actbrow-trace-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class EvalTraceRecorderTests {

	@Autowired
	private EvalTraceRecorder recorder;

	private RunEntity run(String id) {
		RunEntity run = new RunEntity();
		run.setId(id);
		run.setConversationId("conv-1");
		run.setAssistantId("assistant-1");
		return run;
	}

	@Test
	void accumulatesAndPersistsTrace() {
		recorder.begin(run("run-1"), "v1", "t-abc");
		recorder.recordPlanning("run-1", "tool_calls: orders.fetch");
		recorder.recordExecutionAttempt("run-1");
		recorder.recordVerifier("run-1", "SUCCEEDED");
		recorder.finalizeTrace("run-1", "COMPLETED", 1234L);

		RunTraceEntity trace = recorder.findTrace("run-1").orElseThrow();
		assertThat(trace.getAssistantId()).isEqualTo("assistant-1");
		assertThat(trace.getPromptVersion()).isEqualTo("v1");
		assertThat(trace.getToolsetVersion()).isEqualTo("t-abc");
		assertThat(trace.getExecutionAttempts()).isEqualTo(1);
		assertThat(trace.getToolCallCount()).isEqualTo(1);
		assertThat(trace.getFinalOutcome()).isEqualTo("COMPLETED");
		assertThat(trace.getLatencyMs()).isEqualTo(1234L);
		assertThat(trace.getPlanningOutcomes()).contains("orders.fetch");
		assertThat(trace.getVerifierDecisions()).contains("SUCCEEDED");
	}

	@Test
	void finalizeWithoutBeginIsNoOp() {
		recorder.finalizeTrace("never-began", "COMPLETED", 1L);
		assertThat(recorder.findTrace("never-began")).isEmpty();
	}
}
