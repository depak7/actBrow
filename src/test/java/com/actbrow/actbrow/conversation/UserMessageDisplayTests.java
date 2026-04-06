package com.actbrow.actbrow.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserMessageDisplayTests {

	@Test
	void stripRemovesPageContextAppendix() {
		String stored = "Take me to settings" + UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START
			+ "Prefer x.) ---\n{\"url\":\"http://x\"}";
		assertThat(UserMessageDisplay.stripStoredAppendix(stored)).isEqualTo("Take me to settings");
	}

	@Test
	void stripLeavesMessageWithoutMarker() {
		assertThat(UserMessageDisplay.stripStoredAppendix("Hello")).isEqualTo("Hello");
	}
}
