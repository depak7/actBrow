package com.actbrow.actbrow.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.model.RunCheckpointEntity;
import com.actbrow.actbrow.model.RunPhase;
import com.actbrow.actbrow.repository.RunCheckpointRepository;

/**
 * Persists and restores run checkpoints (Phase 4). A checkpoint captures the run's phase, the step
 * it reached, and the last planner/execution/verifier snapshots so an interrupted run can resume
 * from its last durable point rather than restarting the whole cycle.
 */
@Service
public class RunCheckpointService {

	private final RunCheckpointRepository repository;

	public RunCheckpointService(RunCheckpointRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void record(String runId, String conversationId, RunPhase phase, int stepIndex,
		String plannerOutcomeJson, String lastExecutionJson, String verifierResultJson) {
		RunCheckpointEntity checkpoint = repository.findByRunId(runId).orElseGet(RunCheckpointEntity::new);
		checkpoint.setRunId(runId);
		checkpoint.setConversationId(conversationId);
		checkpoint.setPhase(phase);
		checkpoint.setStepIndex(stepIndex);
		if (plannerOutcomeJson != null) {
			checkpoint.setPlannerOutcomeJson(plannerOutcomeJson);
		}
		if (lastExecutionJson != null) {
			checkpoint.setLastExecutionJson(lastExecutionJson);
		}
		if (verifierResultJson != null) {
			checkpoint.setVerifierResultJson(verifierResultJson);
		}
		repository.save(checkpoint);
	}

	/** Lightweight phase-only checkpoint update. */
	@Transactional
	public void recordPhase(String runId, String conversationId, RunPhase phase, int stepIndex) {
		record(runId, conversationId, phase, stepIndex, null, null, null);
	}

	public Optional<RunCheckpointEntity> find(String runId) {
		return repository.findByRunId(runId);
	}

	@Transactional
	public void clear(String runId) {
		repository.deleteByRunId(runId);
	}
}
