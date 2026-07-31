package com.actbrow.actbrow.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.dto.WidgetThemeResponse;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WidgetThemeService {

	private final AssistantService assistantService;
	private final ObjectMapper objectMapper;

	public WidgetThemeService(AssistantService assistantService, ObjectMapper objectMapper) {
		this.assistantService = assistantService;
		this.objectMapper = objectMapper;
	}

	public WidgetThemeResponse get(String assistantId, String userId) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		return new WidgetThemeResponse(assistantId, parseTheme(assistant.getWidgetThemeJson()));
	}

	@Transactional
	public WidgetThemeResponse update(String assistantId, String userId, Map<String, Object> theme) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		Map<String, Object> normalized = theme == null ? Map.of() : new LinkedHashMap<>(theme);
		try {
			assistant.setWidgetThemeJson(objectMapper.writeValueAsString(normalized));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid theme JSON");
		}
		assistantService.saveEntity(assistant);
		return new WidgetThemeResponse(assistantId, normalized);
	}

	public Map<String, Object> themeFor(AssistantDefinitionEntity assistant) {
		return parseTheme(assistant.getWidgetThemeJson());
	}

	private Map<String, Object> parseTheme(String json) {
		if (json == null || json.isBlank()) {
			return defaultTheme();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
			});
			Map<String, Object> merged = defaultTheme();
			merged.putAll(parsed);
			return merged;
		}
		catch (Exception ex) {
			return defaultTheme();
		}
	}

	public static Map<String, Object> defaultTheme() {
		Map<String, Object> theme = new LinkedHashMap<>();
		theme.put("accent", "#10b981");
		theme.put("background", "#0f0f1a");
		theme.put("panelBackground", "linear-gradient(180deg,#1a1a2e 0%,#0f0f1a 100%)");
		theme.put("text", "#e5e5e5");
		theme.put("launcherBackground", "#1a1a1a");
		theme.put("launcherPosition", "bottom-right");
		theme.put("title", "ActBrow Assistant");
		theme.put("subtitle", "Ask, navigate, and act inside this app");
		theme.put("fontFamily", "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif");
		return theme;
	}
}
