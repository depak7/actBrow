package com.actbrow.actbrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.service.ClarificationResponseParser;

class ClarificationResponseParserTests {

	@Test
	void parsesOptionsAndRecommendedChoice() {
		var parsed = ClarificationResponseParser.parse("""
			Which page did you mean?
			OPTIONS: Overview | Orders | Profile
			RECOMMENDED: Overview
			""");

		assertNotNull(parsed);
		assertEquals("Which page did you mean?", parsed.visibleContent());
		assertEquals(List.of("Overview", "Orders", "Profile"), parsed.options());
		assertEquals("Overview", parsed.recommendedOption());
	}

	@Test
	void returnsNullWhenNoOptionsPresent() {
		assertNull(ClarificationResponseParser.parse("Plain answer only"));
	}
}
