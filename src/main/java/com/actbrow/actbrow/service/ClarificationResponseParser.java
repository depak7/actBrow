package com.actbrow.actbrow.service;

import java.util.Arrays;
import java.util.List;

public final class ClarificationResponseParser {

	private ClarificationResponseParser() {
	}

	public static ParsedClarification parse(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		List<String> lines = Arrays.asList(content.split("\\R"));
		int optionsLineIndex = -1;
		List<String> options = List.of();
		String recommended = null;
		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.startsWith("OPTIONS:")) {
				optionsLineIndex = i;
				options = Arrays.stream(trimmed.substring("OPTIONS:".length()).split("\\|"))
					.map(String::trim)
					.filter(s -> !s.isBlank())
					.limit(4)
					.toList();
			}
			else if (trimmed.startsWith("RECOMMENDED:")) {
				recommended = trimmed.substring("RECOMMENDED:".length()).trim();
			}
		}
		if (options.isEmpty()) {
			return null;
		}
		StringBuilder visible = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (i == optionsLineIndex || trimmed.startsWith("RECOMMENDED:")) {
				continue;
			}
			if (!visible.isEmpty()) {
				visible.append('\n');
			}
			visible.append(lines.get(i));
		}
		String visibleContent = visible.toString().trim();
		return new ParsedClarification(content, visibleContent.isBlank() ? content : visibleContent, options, recommended);
	}

	public record ParsedClarification(String rawContent, String visibleContent, List<String> options,
		String recommendedOption) {
	}
}
