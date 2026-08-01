package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.dto.InsightsResponse;
import com.actbrow.actbrow.api.dto.InsightsResponse.IntentCount;
import com.actbrow.actbrow.api.dto.InsightsResponse.RecentFailure;
import com.actbrow.actbrow.api.dto.InsightsResponse.ToolFailureCount;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.model.ConversationEntity;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;
import com.actbrow.actbrow.model.RunStepEntity;
import com.actbrow.actbrow.model.RunStepType;
import com.actbrow.actbrow.repository.ConversationMessageRepository;
import com.actbrow.actbrow.repository.ConversationRepository;
import com.actbrow.actbrow.repository.RunRepository;
import com.actbrow.actbrow.repository.RunStepRepository;

@Service
public class InsightsService {

	private static final Pattern TOOL_KEY_IN_CALL = Pattern.compile("toolKey=([^,\\)\\s]+)|\"toolKey\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern FAILED_RESULT = Pattern.compile("success=false|success=false,|\"success\"\\s*:\\s*false",
		Pattern.CASE_INSENSITIVE);

	private final AssistantService assistantService;
	private final ConversationRepository conversationRepository;
	private final ConversationMessageRepository conversationMessageRepository;
	private final RunRepository runRepository;
	private final RunStepRepository runStepRepository;

	public InsightsService(AssistantService assistantService, ConversationRepository conversationRepository,
		ConversationMessageRepository conversationMessageRepository, RunRepository runRepository,
		RunStepRepository runStepRepository) {
		this.assistantService = assistantService;
		this.conversationRepository = conversationRepository;
		this.conversationMessageRepository = conversationMessageRepository;
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
	}

	@Transactional(readOnly = true)
	public InsightsResponse insights(String assistantId, String userId) {
		assistantService.requireOwnedEntity(assistantId, userId);

		List<ConversationEntity> conversations = conversationRepository.findAllByAssistantId(assistantId);
		List<RunEntity> runs = runRepository.findAllByAssistantIdOrderByCreatedAtDesc(assistantId);

		long completed = runs.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
		long failed = runs.stream().filter(r -> r.getStatus() == RunStatus.FAILED).count();
		long inProgress = runs.stream()
			.filter(r -> r.getStatus() == RunStatus.IN_PROGRESS || r.getStatus() == RunStatus.PENDING
				|| r.getStatus() == RunStatus.WAITING_FOR_CLIENT_TOOL)
			.count();
		long finished = completed + failed;
		double successRate = finished == 0 ? 0.0 : (completed * 100.0) / finished;

		Map<String, Long> intentCounts = new HashMap<>();
		for (ConversationEntity conversation : conversations) {
			List<ConversationMessageEntity> messages = conversationMessageRepository
				.findAllByConversationIdOrderByCreatedAtAscSeqAsc(conversation.getId());
			for (ConversationMessageEntity message : messages) {
				if (message.getRole() != ConversationMessageRole.USER) {
					continue;
				}
				String text = normalizeUserText(message.getContent());
				if (text == null || text.isBlank()) {
					continue;
				}
				intentCounts.merge(text, 1L, Long::sum);
			}
		}

		List<IntentCount> topIntents = intentCounts.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(10)
			.map(e -> new IntentCount(e.getKey(), e.getValue()))
			.toList();

		Map<String, Long> toolFailures = new LinkedHashMap<>();
		for (RunEntity run : runs) {
			List<RunStepEntity> steps = runStepRepository.findAllByRunIdOrderByStepIndexAsc(run.getId());
			String pendingToolKey = null;
			for (RunStepEntity step : steps) {
				if (step.getType() == RunStepType.TOOL_CALL) {
					pendingToolKey = extractToolKey(step.getPayload());
				}
				else if (step.getType() == RunStepType.TOOL_RESULT) {
					if (pendingToolKey != null && FAILED_RESULT.matcher(step.getPayload() == null ? "" : step.getPayload()).find()) {
						toolFailures.merge(pendingToolKey, 1L, Long::sum);
					}
					pendingToolKey = null;
				}
			}
		}

		List<ToolFailureCount> failedTools = toolFailures.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(10)
			.map(e -> new ToolFailureCount(e.getKey(), e.getValue()))
			.toList();

		List<RecentFailure> recentFailures = new ArrayList<>();
		runs.stream()
			.filter(r -> r.getStatus() == RunStatus.FAILED)
			.sorted(Comparator.comparing(RunEntity::getCreatedAt).reversed())
			.limit(8)
			.forEach(r -> recentFailures.add(new RecentFailure(r.getId(),
				r.getLastError() == null ? "Failed" : r.getLastError(), r.getCreatedAt())));

		return new InsightsResponse(assistantId, conversations.size(), runs.size(), completed, failed, inProgress,
			Math.round(successRate * 10) / 10.0, topIntents, failedTools, recentFailures);
	}

	private static String normalizeUserText(String content) {
		if (content == null) {
			return null;
		}
		String withoutContext = content;
		int appendix = content.indexOf(UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START);
		if (appendix >= 0) {
			withoutContext = content.substring(0, appendix).trim();
		}
		String cleaned = withoutContext.replaceAll("\\s+", " ").trim();
		if (cleaned.length() > 120) {
			cleaned = cleaned.substring(0, 117) + "...";
		}
		return cleaned.toLowerCase(Locale.ROOT);
	}

	private static String extractToolKey(String payload) {
		if (payload == null) {
			return "unknown";
		}
		Matcher matcher = TOOL_KEY_IN_CALL.matcher(payload);
		if (matcher.find()) {
			String a = matcher.group(1);
			String b = matcher.group(2);
			return a != null ? a : b;
		}
		return "unknown";
	}
}
