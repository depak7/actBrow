package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.model.FailureType;
import com.actbrow.actbrow.service.RunPolicyEngine.PolicyAction;

class RunPolicyEngineTests {

	private final RunPolicyEngine engine = new RunPolicyEngine();

	@Test
	void successContinues() {
		assertThat(engine.decide(FailureType.NONE, true).action()).isEqualTo(PolicyAction.CONTINUE);
	}

	@Test
	void authNeedsUserRegardlessOfAlternatives() {
		assertThat(engine.decide(FailureType.AUTH_ERROR, true).action())
			.isEqualTo(PolicyAction.REQUIRE_USER_INTERVENTION);
		assertThat(engine.decide(FailureType.AUTH_ERROR, false).action())
			.isEqualTo(PolicyAction.REQUIRE_USER_INTERVENTION);
	}

	@Test
	void clientIncompleteNeedsUser() {
		assertThat(engine.decide(FailureType.CLIENT_INCOMPLETE, true).action())
			.isEqualTo(PolicyAction.REQUIRE_USER_INTERVENTION);
	}

	@Test
	void repairableFailuresRetry() {
		assertThat(engine.decide(FailureType.INVALID_ARGUMENTS, false).action())
			.isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
		assertThat(engine.decide(FailureType.NOT_FOUND, false).action())
			.isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
	}

	@Test
	void transientFailuresRetry() {
		assertThat(engine.decide(FailureType.RATE_LIMITED, false).action())
			.isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
		assertThat(engine.decide(FailureType.TIMEOUT, false).action())
			.isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
	}

	@Test
	void exhaustionSwitchesToolWhenAlternativesExistElseStops() {
		assertThat(engine.decide(FailureType.TOOL_EXHAUSTED, true).action()).isEqualTo(PolicyAction.SWITCH_TOOL);
		assertThat(engine.decide(FailureType.TOOL_EXHAUSTED, false).action())
			.isEqualTo(PolicyAction.STOP_WITH_EXPLANATION);
	}

	@Test
	void unknownSwitchesOrStopsByAlternatives() {
		assertThat(engine.decide(FailureType.UNKNOWN, true).action()).isEqualTo(PolicyAction.SWITCH_TOOL);
		assertThat(engine.decide(FailureType.UNKNOWN, false).action())
			.isEqualTo(PolicyAction.STOP_WITH_EXPLANATION);
	}

	@Test
	void nullFailureTypeTreatedAsUnknown() {
		assertThat(engine.decide(null, false).action()).isEqualTo(PolicyAction.STOP_WITH_EXPLANATION);
	}
}
