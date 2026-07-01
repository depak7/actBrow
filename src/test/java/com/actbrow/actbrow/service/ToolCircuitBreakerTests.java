package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolCircuitBreakerTests {

	private final ToolCircuitBreaker breaker = new ToolCircuitBreaker();

	@Test
	void closedByDefault() {
		assertThat(breaker.allow("orders.fetch")).isTrue();
		assertThat(breaker.isOpen("orders.fetch")).isFalse();
	}

	@Test
	void opensAfterConsecutiveFailures() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("flaky.tool");
		}
		assertThat(breaker.isOpen("flaky.tool")).isTrue();
		assertThat(breaker.allow("flaky.tool")).isFalse();
	}

	@Test
	void successResetsFailureStreak() {
		for (int i = 0; i < 4; i++) {
			breaker.recordFailure("t");
		}
		breaker.recordSuccess("t");
		// Streak reset — four more failures should not trip it yet.
		for (int i = 0; i < 4; i++) {
			breaker.recordFailure("t");
		}
		assertThat(breaker.isOpen("t")).isFalse();
	}

	@Test
	void breakersAreIndependentPerTool() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("bad.tool");
		}
		assertThat(breaker.isOpen("bad.tool")).isTrue();
		assertThat(breaker.allow("good.tool")).isTrue();
	}
}
