package com.actbrow.actbrow.agent;

import java.util.List;

import com.actbrow.actbrow.model.ConversationMessageEntity;

public interface ModelProvider {

	String providerKey();

	ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex);
}
