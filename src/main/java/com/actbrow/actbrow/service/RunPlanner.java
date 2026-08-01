package com.actbrow.actbrow.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ModelProvider;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.RunEntity;

@Service
public class RunPlanner {

	private final ModelProvider modelProvider;
	private final ContextAssembler contextAssembler;

	public RunPlanner(ModelProvider modelProvider, ContextAssembler contextAssembler) {
		this.modelProvider = modelProvider;
		this.contextAssembler = contextAssembler;
	}

	public PlanningOutcome plan(String chatModel, AssistantDefinitionEntity assistant, RunEntity run,
		List<ConversationMessageEntity> messages, List<ToolDescriptor> tools, int stepIndex,
		String baseSystemPrompt, String runtimeGuidance) {
		return plan(chatModel, assistant, run, messages, tools, stepIndex, baseSystemPrompt, runtimeGuidance, null,
			null);
	}

	/**
	 * @param onTextDelta optional sink for incremental text chunks; when non-null the provider streams
	 *                    tokens as they are generated. The returned decision stays authoritative.
	 */
	public PlanningOutcome plan(String chatModel, AssistantDefinitionEntity assistant, RunEntity run,
		List<ConversationMessageEntity> messages, List<ToolDescriptor> tools, int stepIndex,
		String baseSystemPrompt, String runtimeGuidance, java.util.function.Consumer<String> onTextDelta,
		RunMemoryService.RunMemorySnapshot memory) {
		ContextAssembler.ContextAssembly context = contextAssembler.assemble(assistant, run, messages,
			baseSystemPrompt, runtimeGuidance, memory);
		ModelDecision decision = modelProvider.decideNextStep(chatModel, context.systemPrompt(), messages, tools,
			stepIndex, onTextDelta);
		return new PlanningOutcome(decision, context);
	}

	public record PlanningOutcome(
		ModelDecision decision,
		ContextAssembler.ContextAssembly context
	) {
	}
}
