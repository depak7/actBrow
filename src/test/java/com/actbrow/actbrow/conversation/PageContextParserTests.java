package com.actbrow.actbrow.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageContextParserTests {

	@Test
	void extractsPathFromPageContextAppendix() {
		String stored = "What is the refund policy?"
			+ UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START
			+ "Observation only...) ---\n"
			+ "{\"url\":\"https://app.test/settings/billing\",\"path\":\"/settings/billing\",\"title\":\"Billing\"}";

		assertThat(PageContextParser.extractPath(stored)).isEqualTo("/settings/billing");
	}

	@Test
	void returnsNullWhenAppendixMissing() {
		assertThat(PageContextParser.extractPath("plain question")).isNull();
	}
}
