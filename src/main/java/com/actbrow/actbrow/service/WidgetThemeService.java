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

	/** Brand identifier shown in the widget header. Not customisable by operators. */
	public static final String BRAND_TITLE = "ActBrow Assistant";

	private final AssistantService assistantService;
	private final ObjectMapper objectMapper;

	public WidgetThemeService(AssistantService assistantService, ObjectMapper objectMapper) {
		this.assistantService = assistantService;
		this.objectMapper = objectMapper;
	}

	/**
	 * Theme read for the embedded widget. No ownership check: the auth filter has already validated
	 * the widget key and bound it to this assistant, and a widget key has no owning user to compare
	 * against. Theme is public branding — it is visible in the rendered widget either way.
	 */
	public WidgetThemeResponse getForWidget(String assistantId) {
		AssistantDefinitionEntity assistant = assistantService.requireEntity(assistantId);
		return new WidgetThemeResponse(assistantId, parseTheme(assistant.getWidgetThemeJson()));
	}

	public WidgetThemeResponse get(String assistantId, String userId) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		return new WidgetThemeResponse(assistantId, parseTheme(assistant.getWidgetThemeJson()));
	}

	@Transactional
	public WidgetThemeResponse update(String assistantId, String userId, Map<String, Object> theme) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		Map<String, Object> normalized = theme == null ? new LinkedHashMap<>() : new LinkedHashMap<>(theme);
		// The title is brand, not configuration: pin it here so a direct API call cannot white-label
		// the widget even though the dashboard never offers the field.
		normalized.put("title", BRAND_TITLE);
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
			// Rows written before the title was locked (or by an older client) still render as brand.
			merged.put("title", BRAND_TITLE);
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
		theme.put("title", BRAND_TITLE);
		theme.put("subtitle", "Ask, navigate, and act inside this app");
		theme.put("fontFamily", "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif");
		return theme;
	}
}
