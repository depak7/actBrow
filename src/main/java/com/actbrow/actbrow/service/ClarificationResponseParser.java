package com.actbrow.actbrow.service;

import java.util.Arrays;
import java.util.List;

public final class ClarificationResponseParser {

	private static final String OPTIONS_KEY = "OPTIONS:";

	private static final String RECOMMENDED_KEY = "RECOMMENDED:";

	/** Bullets and numbering models like to prepend to a directive line. */
	private static final String LEADING_BULLET = "^(?:[-*•+]\\s+|\\d+[.)]\\s+)";

	/** Markdown emphasis wrapped around a directive keyword, e.g. **OPTIONS:**. */
	private static final String LEADING_EMPHASIS = "^[*_]{1,2}";

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
			String optionsValue = directiveValue(lines.get(i), OPTIONS_KEY);
			if (optionsValue != null) {
				optionsLineIndex = i;
				options = Arrays.stream(optionsValue.split("\\|"))
					.map(ClarificationResponseParser::cleanLabel)
					.filter(s -> !s.isBlank())
					.limit(4)
					.toList();
				continue;
			}
			String recommendedValue = directiveValue(lines.get(i), RECOMMENDED_KEY);
			if (recommendedValue != null) {
				recommended = cleanLabel(recommendedValue);
			}
		}
		if (options.isEmpty()) {
			return null;
		}
		StringBuilder visible = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i == optionsLineIndex || directiveValue(lines.get(i), RECOMMENDED_KEY) != null) {
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

	/**
	 * Returns the value after {@code key} on this line, tolerating the bullets and markdown
	 * emphasis models add despite the prompt, or null when the line is not that directive.
	 */
	private static String directiveValue(String line, String key) {
		if (line == null) {
			return null;
		}
		String candidate = line.trim().replaceFirst(LEADING_BULLET, "").replaceFirst(LEADING_EMPHASIS, "").trim();
		if (candidate.length() < key.length() || !candidate.substring(0, key.length()).equalsIgnoreCase(key)) {
			return null;
		}
		return candidate.substring(key.length()).replaceFirst(LEADING_EMPHASIS, "").trim();
	}

	private static String cleanLabel(String label) {
		return label.trim().replaceAll("^[*_`]+", "").replaceAll("[*_`]+$", "").trim();
	}

	public record ParsedClarification(String rawContent, String visibleContent, List<String> options,
		String recommendedOption) {
	}
}
