package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.conversation.PageContextParser;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.model.RunEntity;

@Service
public class ContextAssembler {

	private final RunMemoryService runMemoryService;

	public ContextAssembler(RunMemoryService runMemoryService) {
		this.runMemoryService = runMemoryService;
	}

	public ContextAssembly assemble(AssistantDefinitionEntity assistant, RunEntity run,
		List<ConversationMessageEntity> messages, String baseSystemPrompt, String runtimeGuidance) {
		RunMemoryService.RunMemorySnapshot memory = runMemoryService.getSnapshot(run.getId());
		String workingMemoryBlock = buildWorkingMemoryBlock(memory);
		String currentStateBlock = buildCurrentStateBlock(messages, memory);
		String historyBlock = buildHistoryBlock(messages);

		StringBuilder systemPrompt = new StringBuilder();
		systemPrompt.append(baseSystemPrompt);
		if (runtimeGuidance != null && !runtimeGuidance.isBlank()) {
			systemPrompt.append(runtimeGuidance);
		}
		systemPrompt.append("LAYERED CONTEXT FOR THIS RUN:\n");
		systemPrompt.append(workingMemoryBlock);
		systemPrompt.append(currentStateBlock);
		systemPrompt.append(historyBlock);
		systemPrompt.append("CONTEXT PRIORITY ORDER:\n");
		systemPrompt.append("  1. Current page/app state and the latest successful tool result.\n");
		systemPrompt.append("  2. Working memory for this run (objective, known entities, blockers, last action).\n");
		systemPrompt.append("  3. Recent conversation history.\n");
		systemPrompt.append("  4. Older history only when still relevant.\n\n");
		systemPrompt.append("Use the layered context explicitly. Do not reconstruct state from stale history when working memory or current state already provides it.\n");

		return new ContextAssembly(systemPrompt.toString(), workingMemoryBlock, currentStateBlock, historyBlock);
	}

	private String buildWorkingMemoryBlock(RunMemoryService.RunMemorySnapshot memory) {
		StringBuilder builder = new StringBuilder();
		builder.append("WORKING MEMORY:\n");
		builder.append("  Objective: ").append(orNone(memory.objective())).append("\n");
		builder.append("  Current step goal: ").append(orNone(memory.currentStepGoal())).append("\n");
		builder.append("  Success criteria: ").append(orNone(memory.successCriteria())).append("\n");
		builder.append("  Known entities: ").append(formatMap(memory.knownEntities())).append("\n");
		builder.append("  Last action: ").append(formatMap(memory.lastAction())).append("\n");
		builder.append("  Blocked reason: ").append(orNone(memory.blockedReason())).append("\n");
		builder.append("  Recent failures: ").append(formatFailures(memory.lastFailures())).append("\n");
		builder.append("  Summary: ").append(formatMap(memory.summary())).append("\n\n");
		return builder.toString();
	}

	private String buildCurrentStateBlock(List<ConversationMessageEntity> messages,
		RunMemoryService.RunMemorySnapshot memory) {
		String latestUserPath = null;
		String latestUserMessage = null;
		for (int index = messages.size() - 1; index >= 0; index--) {
			ConversationMessageEntity message = messages.get(index);
			if (message.getRole() != ConversationMessageRole.USER) {
				continue;
			}
			latestUserPath = PageContextParser.extractPath(message.getContent());
			latestUserMessage = compact(UserMessageDisplay.stripStoredAppendix(message.getContent()), 240);
			break;
		}
		StringBuilder builder = new StringBuilder();
		builder.append("CURRENT STATE:\n");
		builder.append("  Assistant: ").append(assistantLabel(memory, latestUserPath)).append("\n");
		builder.append("  Latest user message: ").append(orNone(latestUserMessage)).append("\n");
		builder.append("  Latest observed path: ").append(orNone(latestUserPath)).append("\n");
		builder.append("  Page state hint: ")
			.append(orNone(stringValue(memory.summary().get("status"))))
			.append("\n\n");
		return builder.toString();
	}

	private String buildHistoryBlock(List<ConversationMessageEntity> messages) {
		List<String> lines = new ArrayList<>();
		int start = Math.max(0, messages.size() - 6);
		for (int index = start; index < messages.size(); index++) {
			ConversationMessageEntity message = messages.get(index);
			lines.add(message.getRole().name() + ": " + summarizeMessage(message));
		}
		StringBuilder builder = new StringBuilder();
		builder.append("RECENT HISTORY:\n");
		if (lines.isEmpty()) {
			builder.append("  (none)\n\n");
			return builder.toString();
		}
		for (String line : lines) {
			builder.append("  - ").append(line).append("\n");
		}
		builder.append("\n");
		return builder.toString();
	}

	private String summarizeMessage(ConversationMessageEntity message) {
		String content = message.getContent();
		if (message.getRole() == ConversationMessageRole.USER) {
			return compact(UserMessageDisplay.stripStoredAppendix(content), 220);
		}
		return compact(content, 220);
	}

	private String assistantLabel(RunMemoryService.RunMemorySnapshot memory, String latestUserPath) {
		String path = latestUserPath;
		if ((path == null || path.isBlank()) && memory.knownEntities().containsKey("path")) {
			path = stringValue(memory.knownEntities().get("path"));
		}
		return path == null || path.isBlank() ? "embedded SaaS assistant" : "embedded SaaS assistant at " + path;
	}

	private String formatMap(Map<String, Object> values) {
		if (values == null || values.isEmpty()) {
			return "(none)";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			parts.add(entry.getKey() + "=" + compact(stringValue(entry.getValue()), 80));
		}
		return String.join(", ", parts);
	}

	private String formatFailures(List<Map<String, Object>> failures) {
		if (failures == null || failures.isEmpty()) {
			return "(none)";
		}
		List<String> parts = new ArrayList<>();
		for (Map<String, Object> failure : failures) {
			parts.add(compact(stringValue(failure.get("toolKey")) + ": " + stringValue(failure.get("error")), 120));
		}
		return String.join(" | ", parts);
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String compact(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= maxLength) {
			return normalized;
		}
		return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
	}

	private static String orNone(String value) {
		return value == null || value.isBlank() ? "(none)" : value;
	}

	public record ContextAssembly(
		String systemPrompt,
		String workingMemoryBlock,
		String currentStateBlock,
		String historyBlock
	) {
	}
}
