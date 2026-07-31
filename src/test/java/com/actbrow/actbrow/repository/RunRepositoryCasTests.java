package com.actbrow.actbrow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;

/**
 * Invariant tests for the guarded run-status transitions. These queries are the concurrency
 * control for the whole harness: exactly one worker may claim a run, terminal states are final,
 * and a worker that lost ownership observes it via a zero row count.
 */
@DataJpaTest
class RunRepositoryCasTests {

	@Autowired
	private RunRepository runRepository;

	private RunEntity newRun(RunStatus status) {
		RunEntity run = new RunEntity();
		run.setConversationId("conv-1");
		run.setAssistantId("assistant-1");
		run.setStatus(status);
		run.setStepCount(0);
		return runRepository.saveAndFlush(run);
	}

	private RunEntity reload(String id) {
		return runRepository.findById(id).orElseThrow();
	}

	@Test
	void claimIsExclusive() {
		RunEntity run = newRun(RunStatus.PENDING);
		Instant now = Instant.now();
		Instant staleBefore = now.minusSeconds(300);

		assertThat(runRepository.claimForExecution(run.getId(), now, staleBefore)).isEqualTo(1);
		// Second claim loses: the run is IN_PROGRESS with a fresh heartbeat.
		assertThat(runRepository.claimForExecution(run.getId(), now, staleBefore)).isZero();

		RunEntity claimed = reload(run.getId());
		assertThat(claimed.getStatus()).isEqualTo(RunStatus.IN_PROGRESS);
		assertThat(claimed.getClaimedAt()).isNotNull();
	}

	@Test
	void staleInFlightRunCanBeReclaimedButFreshOneCannot() {
		RunEntity run = newRun(RunStatus.IN_PROGRESS);
		Instant now = Instant.now();

		run.setClaimedAt(now.minusSeconds(30));
		runRepository.saveAndFlush(run);
		// Heartbeat is 30s old, staleness threshold is 300s — a live worker owns it.
		assertThat(runRepository.claimForExecution(run.getId(), now, now.minusSeconds(300))).isZero();

		run = reload(run.getId());
		run.setClaimedAt(now.minusSeconds(600));
		runRepository.saveAndFlush(run);
		// Heartbeat older than the threshold — the previous owner died; reclaim succeeds.
		assertThat(runRepository.claimForExecution(run.getId(), now, now.minusSeconds(300))).isEqualTo(1);
	}

	@Test
	void terminalStatesAreFinal() {
		RunEntity run = newRun(RunStatus.IN_PROGRESS);
		Instant now = Instant.now();

		assertThat(runRepository.finishIfActive(run.getId(), RunStatus.CANCELLED, "Cancelled by client", now))
			.isEqualTo(1);
		// A worker finishing late can no longer flip the cancelled run to COMPLETED or FAILED.
		assertThat(runRepository.finishIfActive(run.getId(), RunStatus.COMPLETED, null, now)).isZero();
		assertThat(runRepository.finishIfActive(run.getId(), RunStatus.FAILED, "boom", now)).isZero();
		// Nor can it be re-claimed for execution, no matter how stale the heartbeat looks.
		assertThat(runRepository.claimForExecution(run.getId(), now, now.plusSeconds(3600))).isZero();

		RunEntity finished = reload(run.getId());
		assertThat(finished.getStatus()).isEqualTo(RunStatus.CANCELLED);
		assertThat(finished.getLastError()).isEqualTo("Cancelled by client");
		assertThat(finished.getCompletedAt()).isNotNull();
	}

	@Test
	void heartbeatStopsAfterCancellation() {
		RunEntity run = newRun(RunStatus.IN_PROGRESS);
		Instant now = Instant.now();

		assertThat(runRepository.recordProgress(run.getId(), 1, now)).isEqualTo(1);
		runRepository.finishIfActive(run.getId(), RunStatus.CANCELLED, "Cancelled by client", now);
		// The owning worker's next heartbeat returns 0 — its signal to stop, not overwrite.
		assertThat(runRepository.recordProgress(run.getId(), 2, now)).isZero();
		assertThat(reload(run.getId()).getStepCount()).isEqualTo(1);
	}

	@Test
	void transitionIsCompareAndSet() {
		RunEntity run = newRun(RunStatus.IN_PROGRESS);
		Instant now = Instant.now();

		assertThat(runRepository.transition(run.getId(), RunStatus.IN_PROGRESS,
			RunStatus.WAITING_FOR_CLIENT_TOOL, now)).isEqualTo(1);
		// Re-applying the same transition fails: status is no longer IN_PROGRESS.
		assertThat(runRepository.transition(run.getId(), RunStatus.IN_PROGRESS,
			RunStatus.WAITING_FOR_CLIENT_TOOL, now)).isZero();

		runRepository.finishIfActive(run.getId(), RunStatus.CANCELLED, "Cancelled by client", now);
		// A cancelled run cannot be moved back to IN_PROGRESS by a late worker.
		assertThat(runRepository.transition(run.getId(), RunStatus.WAITING_FOR_CLIENT_TOOL,
			RunStatus.IN_PROGRESS, now)).isZero();
	}

	@Test
	void orphanFindersMatchOnlyOrphans() {
		Instant now = Instant.now();

		RunEntity freshPending = newRun(RunStatus.PENDING);
		RunEntity staleInFlight = newRun(RunStatus.IN_PROGRESS);
		staleInFlight.setClaimedAt(now.minusSeconds(900));
		runRepository.saveAndFlush(staleInFlight);
		RunEntity healthyInFlight = newRun(RunStatus.IN_PROGRESS);
		healthyInFlight.setClaimedAt(now);
		runRepository.saveAndFlush(healthyInFlight);

		List<RunEntity> stale = runRepository.findByStatusInAndClaimedAtBefore(
			List.of(RunStatus.IN_PROGRESS, RunStatus.WAITING_FOR_CLIENT_TOOL), now.minusSeconds(300));
		assertThat(stale).extracting(RunEntity::getId).containsExactly(staleInFlight.getId());

		List<RunEntity> pendingOrphans = runRepository.findByStatusAndCreatedAtBefore(RunStatus.PENDING,
			now.plusSeconds(60));
		assertThat(pendingOrphans).extracting(RunEntity::getId).contains(freshPending.getId());
	}
}
