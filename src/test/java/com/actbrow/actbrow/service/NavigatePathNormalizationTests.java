package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NavigatePathNormalizationTests {

	@Test
	void prefixesRelativePathsSoHostRoutersDoNotJoinCurrentRoute() {
		assertThat(RunService.normalizeAppNavigatePath("deepak/60min")).isEqualTo("/deepak/60min");
		assertThat(RunService.normalizeAppNavigatePath("  bookings  ")).isEqualTo("/bookings");
	}

	@Test
	void leavesAbsoluteAndSpecialFormsAlone() {
		assertThat(RunService.normalizeAppNavigatePath("/deepak/60min")).isEqualTo("/deepak/60min");
		assertThat(RunService.normalizeAppNavigatePath("?dialog=new")).isEqualTo("?dialog=new");
		assertThat(RunService.normalizeAppNavigatePath("#section")).isEqualTo("#section");
		assertThat(RunService.normalizeAppNavigatePath("https://example.com/x")).isEqualTo("https://example.com/x");
		assertThat(RunService.normalizeAppNavigatePath("")).isEqualTo("");
	}
}
