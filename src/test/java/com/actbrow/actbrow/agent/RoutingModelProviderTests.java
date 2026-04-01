package com.actbrow.actbrow.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.config.LlmProperties;

class RoutingModelProviderTests {

	@Test
	void routesUsingProviderPrefixInModelName() {
		RecordingProvider gemini = new RecordingProvider("gemini");
		RecordingProvider sarvam = new RecordingProvider("sarvam");
		RoutingModelProvider provider = new RoutingModelProvider(List.of(gemini, sarvam),
			new LlmProperties("gemini"));

		provider.decideNextStep("sarvam:sarvam-m", "prompt", List.of(), List.of(), 0);

		assertEquals("sarvam-m", sarvam.lastModel);
		assertEquals(null, gemini.lastModel);
	}

	@Test
	void fallsBackToDefaultProviderWhenNoPrefixIsPresent() {
		RecordingProvider gemini = new RecordingProvider("gemini");
		RecordingProvider sarvam = new RecordingProvider("sarvam");
		RoutingModelProvider provider = new RoutingModelProvider(List.of(gemini, sarvam),
			new LlmProperties("sarvam"));

		provider.decideNextStep("sarvam-chat", "prompt", List.of(), List.of(), 0);

		assertEquals("sarvam-chat", sarvam.lastModel);
	}

	private static final class RecordingProvider implements ModelProvider {
		private final String key;
		private String lastModel;

		private RecordingProvider(String key) {
			this.key = key;
		}

		@Override
		public String providerKey() {
			return key;
		}

		@Override
		public ModelDecision decideNextStep(String model, String systemPrompt,
			List<com.actbrow.actbrow.model.ConversationMessageEntity> messages, List<ToolDescriptor> tools, int stepIndex) {
			this.lastModel = model;
			return new FinalResponseDecision("ok");
		}
	}
}
