package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HarnessPromptContractTests {

	@Test
	void turnContractEncodesBoundedEfficiencyAndEvidence() {
		String turn = HarnessPromptContract.turnEfficiencyContract();
		assertThat(turn).contains("TURN CONTRACT");
		assertThat(turn).contains("DONE-WHEN");
		assertThat(turn).contains("EVIDENCE");
		assertThat(turn).containsIgnoringCase("think deeply");
		assertThat(turn).contains("OPTIONS");
	}

	@Test
	void evidencePriorityPutsToolResultsFirst() {
		String rules = HarnessPromptContract.evidencePriorityRules();
		assertThat(rules).contains("PAGE_CONTEXT");
		assertThat(rules).contains("successful tool result");
		assertThat(rules).contains("Never invent");
	}

	@Test
	void failureRecoveryIsThreePartRecipe() {
		String rules = HarnessPromptContract.failureRecoveryRules();
		assertThat(rules).contains("What failed");
		assertThat(rules).contains("still safe");
		assertThat(rules).contains("Next valid action");
	}

	@Test
	void verifyBeforeDoneBlocksFakeSuccess() {
		String rules = HarnessPromptContract.verifyBeforeDoneRules();
		assertThat(rules).contains("success=false");
		assertThat(rules).contains("pageObserve");
	}
}
