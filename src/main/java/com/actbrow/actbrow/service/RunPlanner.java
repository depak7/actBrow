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
		ContextAssembler.ContextAssembly context = contextAssembler.assemble(assistant, run, messages,
			baseSystemPrompt, runtimeGuidance);
		ModelDecision decision = modelProvider.decideNextStep(chatModel, context.systemPrompt(), messages, tools, stepIndex);
		return new PlanningOutcome(decision, context);
	}

	public record PlanningOutcome(
		ModelDecision decision,
		ContextAssembler.ContextAssembly context
	) {
	}
}
