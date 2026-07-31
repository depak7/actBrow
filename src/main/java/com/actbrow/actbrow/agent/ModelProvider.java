package com.actbrow.actbrow.agent;

import java.util.List;
import java.util.function.Consumer;

import com.actbrow.actbrow.model.ConversationMessageEntity;

public interface ModelProvider {

	ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex);

	/**
	 * Like {@link #decideNextStep(String, String, List, List, int)}, but when the provider supports
	 * token streaming, incremental TEXT chunks are pushed to {@code onTextDelta} as they are
	 * generated. Deltas are a UI hint only — the returned {@link ModelDecision} remains the single
	 * authoritative result, and tool-call decisions never surface as deltas. Providers without
	 * streaming support fall back to the blocking call.
	 */
	default ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex, Consumer<String> onTextDelta) {
		return decideNextStep(model, systemPrompt, messages, tools, stepIndex);
	}
}
