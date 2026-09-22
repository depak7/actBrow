package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NavigationClaimGuardTests {

	@Test
	void flagsTheExactClaimSeenInProduction() {
		// Emitted with no navigation tool call when the user asked for a page visited earlier.
		assertThat(NavigationClaimGuard.claimsNavigation(
			"You are now on the Docs page. Here, you'll find documentation on how to integrate ActBrow."))
			.isTrue();
	}

	@Test
	void flagsOtherPhrasingsOfMovement() {
		assertThat(NavigationClaimGuard.claimsNavigation("I've taken you to the pricing page.")).isTrue();
		assertThat(NavigationClaimGuard.claimsNavigation("I have navigated to /docs for you.")).isTrue();
		assertThat(NavigationClaimGuard.claimsNavigation("Done — I brought you to Settings.")).isTrue();
	}

	@Test
	void flagsTypographicApostrophes() {
		assertThat(NavigationClaimGuard.claimsNavigation("You’re now on the React example page.")).isTrue();
		assertThat(NavigationClaimGuard.claimsNavigation("I’ve taken you to Pricing.")).isTrue();
	}

	@Test
	void ignoresStatementsOfCurrentLocation() {
		// Answering "where am I?" from page context is grounded, not a claim that anything moved.
		assertThat(NavigationClaimGuard.claimsNavigation("You're on the Pricing page right now.")).isFalse();
		assertThat(NavigationClaimGuard.claimsNavigation("You are on the Docs page.")).isFalse();
	}

	@Test
	void ignoresOrdinaryAnswersAndOffers() {
		assertThat(NavigationClaimGuard.claimsNavigation(
			"Yes, ActBrow is open source. Would you like me to take you to the docs?")).isFalse();
		assertThat(NavigationClaimGuard.claimsNavigation("It supports REST, MCP and knowledge tools.")).isFalse();
		assertThat(NavigationClaimGuard.claimsNavigation(null)).isFalse();
		assertThat(NavigationClaimGuard.claimsNavigation("  ")).isFalse();
	}
}
