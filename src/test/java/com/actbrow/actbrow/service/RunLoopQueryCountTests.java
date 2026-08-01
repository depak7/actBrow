package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ModelProvider;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.api.dto.ConversationRequest;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.TurnRequest;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.RunStatus;

import jakarta.persistence.EntityManagerFactory;

/**
 * Measures how many JDBC statements one agent turn actually costs, and fails if that regresses.
 *
 * <p>Query count is the metric rather than wall-clock time because the model call dominates latency
 * and would swamp any timing signal; statement count is deterministic and attributable. The bound is
 * deliberately generous — this guards against a reintroduced N+1 or a per-token read, not against
 * small refactors.
 */
@SpringBootTest(properties = {
	"spring.ai.openai.api-key=test-key",
	"spring.ai.openai.base-url=http://localhost:9999",
	"spring.ai.openai.chat.options.model=test-model",
	"spring.datasource.url=jdbc:h2:mem:actbrow-querycount;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.properties.hibernate.generate_statistics=true",
	// src/test/resources/application.properties shadows the main one, so the agent config that
	// ActbrowProperties binds is not present by default in tests.
	"actbrow.agent.max-steps=15",
	"actbrow.agent.tool-timeout=10s",
	"actbrow.agent.max-tool-retries=2"
})
@Import(RunLoopQueryCountTests.ScriptedModelConfig.class)
class RunLoopQueryCountTests {

	/** Replaces the real provider so no HTTP happens and the decision sequence is deterministic. */
	@TestConfiguration
	static class ScriptedModelConfig {

		static final AtomicInteger DELTA_COUNT = new AtomicInteger();
		/** When set, the first planning step returns this tool call instead of a final answer. */
		static volatile String TOOL_KEY_TO_CALL = null;
		static final AtomicInteger STEP_COUNT = new AtomicInteger();

		@Bean
		@Primary
		ModelProvider scriptedModelProvider() {
			return new ModelProvider() {
				@Override
				public ModelDecision decideNextStep(String model, String systemPrompt,
					List<ConversationMessageEntity> messages, List<ToolDescriptor> tools, int stepIndex) {
					return new FinalResponseDecision("Done.");
				}

				@Override
				public ModelDecision decideNextStep(String model, String systemPrompt,
					List<ConversationMessageEntity> messages, List<ToolDescriptor> tools, int stepIndex,
					java.util.function.Consumer<String> onTextDelta) {
					int step = STEP_COUNT.incrementAndGet();
					String toolKey = TOOL_KEY_TO_CALL;
					if (toolKey != null && step == 1) {
						ToolDescriptor target = tools.stream()
							.filter(t -> toolKey.equals(t.key()))
							.findFirst()
							.orElseThrow(() -> new IllegalStateException("tool not disclosed: " + toolKey));
						return new com.actbrow.actbrow.agent.ToolCallDecision("call " + toolKey,
							List.of(new com.actbrow.actbrow.agent.ToolCall("tc-1", target.id(), target.key(),
								java.util.Map.of())));
					}
					// Emit a realistic number of chunks: the whole point of the fix is that streaming
					// a long answer must not scale database reads with token count.
					if (onTextDelta != null) {
						for (int i = 0; i < 200; i++) {
							onTextDelta.accept("token ");
							DELTA_COUNT.incrementAndGet();
						}
					}
					return new FinalResponseDecision("Done.");
				}
			};
		}
	}

	@Autowired
	private AssistantService assistantService;
	@Autowired
	private ConversationService conversationService;
	@Autowired
	private RunService runService;
	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	/** Polls issue their own query each time, so callers subtract this to keep the number attributable. */
	private final AtomicInteger pollCount = new AtomicInteger();

	private void awaitTerminal(String runId) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
		while (Instant.now().isBefore(deadline)) {
			pollCount.incrementAndGet();
			RunStatus status = runService.getRun(runId).status();
			if (status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
				return;
			}
			try {
				Thread.sleep(25);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new AssertionError("Run did not reach a terminal state in time: " + runId);
	}

	@Test
	void oneTurnStaysWithinItsQueryBudget() {
		AssistantResponse assistant = assistantService.createOrUpdate(
			new CreateAssistantRequest("Perf Assistant", "be helpful", null, false, "perf-user"));
		ConversationResponse conversation = conversationService.create(new ConversationRequest(assistant.id()));

		Statistics stats = statistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		pollCount.set(0);
		ScriptedModelConfig.DELTA_COUNT.set(0);

		RunResponse run = runService.startRun(conversation.id(), new TurnRequest("hello", null));
		awaitTerminal(run.id());

		long statements = stats.getPrepareStatementCount() - pollCount.get();
		System.out.println("[query-count] one turn = " + statements + " JDBC statements (excluding "
			+ pollCount.get() + " polls), " + ScriptedModelConfig.DELTA_COUNT.get() + " streamed tokens");

		assertThat(runService.getRun(run.id()).status()).isEqualTo(RunStatus.COMPLETED);
		assertThat(ScriptedModelConfig.DELTA_COUNT.get()).isEqualTo(200);

		// 200 tokens streamed. Before the fix each one issued its own SELECT on the run row, so a
		// budget well under the token count is what proves the per-token read is gone.
		assertThat(statements)
			.as("one turn should cost far fewer statements than it streams tokens")
			.isLessThan(120);
	}

	/**
	 * Pins the decision that nothing on the run path is cached.
	 *
	 * <p>The tool catalog and assistant definition were cached at one point and removed again: the
	 * measured saving was 2 statements out of ~31 on a turn dominated by the model call, while
	 * {@code AssistantService.requireEntity} returns an entity that callers mutate in place — so a
	 * cache hit handed two threads the same mutable object and published uncommitted writes. If
	 * someone reintroduces entity caching here, this test starts failing and points at that trade.
	 */
	@Test
	void runPathReadsAreNotCached() {
		AssistantResponse assistant = assistantService.createOrUpdate(
			new CreateAssistantRequest("Warm Assistant", "be helpful", null, false, "warm-user"));
		ConversationResponse conversation = conversationService.create(new ConversationRequest(assistant.id()));

		Statistics stats = statistics();
		stats.setStatisticsEnabled(true);

		stats.clear();
		pollCount.set(0);
		RunResponse first = runService.startRun(conversation.id(), new TurnRequest("hello", null));
		awaitTerminal(first.id());
		long cold = stats.getPrepareStatementCount() - pollCount.get();

		stats.clear();
		pollCount.set(0);
		RunResponse second = runService.startRun(conversation.id(), new TurnRequest("hello again", null));
		awaitTerminal(second.id());
		long warm = stats.getPrepareStatementCount() - pollCount.get();

		System.out.println("[query-count] first turn = " + cold + ", second turn = " + warm
			+ " JDBC statements (same assistant)");

		assertThat(runService.getRun(second.id()).status()).isEqualTo(RunStatus.COMPLETED);
		// Equal, not lower: the run path deliberately reads through to the database every turn.
		assertThat(warm)
			.as("a second turn must cost the same — no entity on the run path may be cached")
			.isEqualTo(cold);
	}

	@Test
	void turnWithAToolCallStaysWithinItsQueryBudget() {
		AssistantResponse assistant = assistantService.createOrUpdate(
			new CreateAssistantRequest("Perf Tool Assistant", "be helpful", null, false, "perf-user-2"));
		ConversationResponse conversation = conversationService.create(new ConversationRequest(assistant.id()));

		// knowledge.search is the one built-in that executes server-side. path.find and
		// page.screenshot are dispatched into the browser, so in a headless test they would park on
		// the pending-tool future for the full tool timeout and measure the timeout, not the loop.
		ScriptedModelConfig.TOOL_KEY_TO_CALL = "knowledge.search";
		ScriptedModelConfig.STEP_COUNT.set(0);
		ScriptedModelConfig.DELTA_COUNT.set(0);

		Statistics stats = statistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		pollCount.set(0);
		try {
			RunResponse run = runService.startRun(conversation.id(), new TurnRequest("where am i", null));
			awaitTerminal(run.id());

			long statements = stats.getPrepareStatementCount() - pollCount.get();
			System.out.println("[query-count] turn with 1 tool call = " + statements
				+ " JDBC statements (excluding " + pollCount.get() + " polls) over "
				+ ScriptedModelConfig.STEP_COUNT.get() + " planning steps");

			assertThat(ScriptedModelConfig.STEP_COUNT.get()).isGreaterThanOrEqualTo(2);
			assertThat(statements)
				.as("a two-step turn must not scale with tokens or catalog size")
				.isLessThan(160);
		}
		finally {
			ScriptedModelConfig.TOOL_KEY_TO_CALL = null;
		}
	}
}
