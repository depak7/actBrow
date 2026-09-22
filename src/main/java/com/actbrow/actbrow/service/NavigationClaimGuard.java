package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Locale;

/**
 * Detects a final answer that tells the user they were moved to a page.
 *
 * <p>The planner can skip the navigation tool and still reply "You are now on the Pricing page",
 * copying the shape of its own earlier replies in the conversation history. The page never moves,
 * and the user is told it did. The run loop uses this to refuse such an answer when no navigation
 * tool succeeded in the current run.
 *
 * <p>The phrases are claims of <em>movement</em> ("now on", "taken you"), not statements of
 * location: "You're on the Pricing page" answering "where am I?" is legitimately grounded in page
 * context and must not trip the guard.
 */
public final class NavigationClaimGuard {

	private static final List<String> CLAIMS = List.of(
		"you are now on",
		"you're now on",
		"i've taken you",
		"i have taken you",
		"i took you",
		"taken you to",
		"i've navigated",
		"i have navigated",
		"navigated you",
		"brought you to",
		"you've been taken",
		"you have been taken");

	private NavigationClaimGuard() {
	}

	public static boolean claimsNavigation(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		// Models emit typographic apostrophes as often as straight ones.
		String normalized = message.toLowerCase(Locale.ROOT).replace('’', '\'');
		for (String claim : CLAIMS) {
			if (normalized.contains(claim)) {
				return true;
			}
		}
		return false;
	}
}
