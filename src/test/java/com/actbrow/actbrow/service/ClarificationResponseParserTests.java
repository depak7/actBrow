package com.actbrow.actbrow.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationResponseParserTests {

	@Test
	@DisplayName("parses the exact prompted format and hides the control lines")
	void parsesPromptedFormat() {
		var parsed = ClarificationResponseParser.parse("""
			Which event type should I use?
			OPTIONS: 15 Min Meeting | 30 Min Intro Call | Secret Meeting
			RECOMMENDED: 30 Min Intro Call""");

		assertThat(parsed).isNotNull();
		assertThat(parsed.options()).containsExactly("15 Min Meeting", "30 Min Intro Call", "Secret Meeting");
		assertThat(parsed.recommendedOption()).isEqualTo("30 Min Intro Call");
		assertThat(parsed.visibleContent()).isEqualTo("Which event type should I use?");
	}

	@Test
	@DisplayName("tolerates markdown emphasis around the directive keyword")
	void toleratesMarkdownEmphasis() {
		var parsed = ClarificationResponseParser.parse("""
			Pick a time.
			**OPTIONS:** 1:30 PM | 2:00 PM | 3:15 PM
			**RECOMMENDED:** 2:00 PM""");

		assertThat(parsed).isNotNull();
		assertThat(parsed.options()).containsExactly("1:30 PM", "2:00 PM", "3:15 PM");
		assertThat(parsed.recommendedOption()).isEqualTo("2:00 PM");
		assertThat(parsed.visibleContent()).isEqualTo("Pick a time.");
	}

	@Test
	@DisplayName("tolerates a bulleted directive line")
	void toleratesBullet() {
		var parsed = ClarificationResponseParser.parse("""
			Which one?
			- OPTIONS: Yes | No""");

		assertThat(parsed).isNotNull();
		assertThat(parsed.options()).containsExactly("Yes", "No");
		assertThat(parsed.visibleContent()).isEqualTo("Which one?");
	}

	@Test
	@DisplayName("caps options at four")
	void capsAtFour() {
		var parsed = ClarificationResponseParser.parse("Pick:\nOPTIONS: a | b | c | d | e");

		assertThat(parsed).isNotNull();
		assertThat(parsed.options()).containsExactly("a", "b", "c", "d");
	}

	@Test
	@DisplayName("returns null when the answer carries no options")
	void nullWithoutOptions() {
		assertThat(ClarificationResponseParser.parse("Please tell me which event type to use.")).isNull();
		assertThat(ClarificationResponseParser.parse("")).isNull();
		assertThat(ClarificationResponseParser.parse(null)).isNull();
	}
}
