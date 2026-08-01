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

	@Test
	void snapshotFiltersByAssistantPrefixAndStripsIt() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("a1|orders.fetch");
		}
		breaker.allow("a1|orders.refund");
		breaker.allow("a2|orders.fetch");

		assertThat(breaker.snapshotFor("a1"))
			.containsOnlyKeys("orders.fetch", "orders.refund")
			.containsEntry("orders.fetch", true)
			.containsEntry("orders.refund", false);
	}

	@Test
	void snapshotIsEmptyForUnknownAssistant() {
		breaker.allow("a1|orders.fetch");
		assertThat(breaker.snapshotFor("a2")).isEmpty();
		assertThat(breaker.snapshotFor(null)).isEmpty();
	}

	@Test
	void snapshotDoesNotConsumeTheHalfOpenProbe() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("a1|flaky.tool");
		}
		breaker.snapshotFor("a1");
		// Reading the dashboard must not move the circuit to half-open and hand out a free call.
		assertThat(breaker.allow("a1|flaky.tool")).isFalse();
	}

	@Test
	void resetClosesAnOpenCircuit() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("a1|flaky.tool");
		}
		assertThat(breaker.isOpen("a1|flaky.tool")).isTrue();

		breaker.reset("a1|flaky.tool");

		assertThat(breaker.isOpen("a1|flaky.tool")).isFalse();
		assertThat(breaker.allow("a1|flaky.tool")).isTrue();
		assertThat(breaker.snapshotFor("a1")).containsEntry("flaky.tool", false);
	}

	@Test
	void resetAlsoClearsTheFailureStreak() {
		for (int i = 0; i < 5; i++) {
			breaker.recordFailure("a1|flaky.tool");
		}
		breaker.reset("a1|flaky.tool");
		// A single post-reset failure must not immediately re-open the circuit.
		breaker.recordFailure("a1|flaky.tool");
		assertThat(breaker.isOpen("a1|flaky.tool")).isFalse();
	}

	@Test
	void resetOfUnknownKeyIsANoOp() {
		breaker.reset("a1|never.seen");

		assertThat(breaker.snapshotFor("a1")).isEmpty();
		assertThat(breaker.allow("a1|never.seen")).isTrue();
	}
}
