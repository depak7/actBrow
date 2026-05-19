package com.actbrow.actbrow.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.dto.KnowledgeDocumentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class KnowledgeSearchToolExecutor {

	private static final int DEFAULT_LIMIT = 5;

	private final KnowledgeService knowledgeService;
	private final ObjectMapper objectMapper;

	public KnowledgeSearchToolExecutor(KnowledgeService knowledgeService, ObjectMapper objectMapper) {
		this.knowledgeService = knowledgeService;
		this.objectMapper = objectMapper;
	}

	public ToolExecutionResult execute(String assistantId, Map<String, Object> arguments) {
		String query = stringArg(arguments, "query");
		if (query == null || query.isBlank()) {
			return failure("query is required");
		}
		String path = stringArg(arguments, "path");
		List<KnowledgeDocumentResponse> documents = knowledgeService.findRelevant(assistantId, query, path,
			DEFAULT_LIMIT);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("query", query);
		if (path != null && !path.isBlank()) {
			payload.put("path", path);
		}
		payload.put("count", documents.size());
		payload.put("results", documents.stream().map(this::toResult).toList());
		if (documents.isEmpty()) {
			payload.put("message", "No matching knowledge documents.");
		}
		try {
			String structured = objectMapper.writeValueAsString(payload);
			String summary = documents.isEmpty()
				? "No knowledge documents matched query \"" + query + "\""
				: "Found " + documents.size() + " knowledge document(s) for query \"" + query + "\"";
			return new ToolExecutionResult(true, structured, summary, null);
		}
		catch (JsonProcessingException exception) {
			return failure("Unable to serialize knowledge search results");
		}
	}

	private Map<String, Object> toResult(KnowledgeDocumentResponse document) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("title", document.title());
		result.put("content", document.content());
		if (document.source() != null && !document.source().isBlank()) {
			result.put("source", document.source());
		}
		return result;
	}

	private static String stringArg(Map<String, Object> arguments, String key) {
		if (arguments == null) {
			return null;
		}
		Object value = arguments.get(key);
		return value == null ? null : String.valueOf(value).trim();
	}

	private ToolExecutionResult failure(String message) {
		return new ToolExecutionResult(false, null, message, message);
	}
}
