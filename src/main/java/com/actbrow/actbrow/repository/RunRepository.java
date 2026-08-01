package com.actbrow.actbrow.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;

public interface RunRepository extends JpaRepository<RunEntity, String> {

	List<RunEntity> findAllByConversationId(String conversationId);

	List<RunEntity> findAllByAssistantIdOrderByCreatedAtDesc(String assistantId);

	long countByAssistantId(String assistantId);

	long countByAssistantIdAndStatus(String assistantId, com.actbrow.actbrow.model.RunStatus status);

	/**
	 * Atomically claims a run for execution. Succeeds (returns 1) when the run is PENDING, or when it
	 * is IN_PROGRESS/WAITING_FOR_CLIENT_TOOL with a stale heartbeat — i.e. the previous owner died.
	 * The single UPDATE is the ownership gate: at most one caller across all instances wins.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RunEntity r set r.status = com.actbrow.actbrow.model.RunStatus.IN_PROGRESS, r.claimedAt = :now "
		+ "where r.id = :runId and (r.status = com.actbrow.actbrow.model.RunStatus.PENDING "
		+ "or (r.status in (com.actbrow.actbrow.model.RunStatus.IN_PROGRESS, com.actbrow.actbrow.model.RunStatus.WAITING_FOR_CLIENT_TOOL) "
		+ "and (r.claimedAt is null or r.claimedAt < :staleBefore)))")
	int claimForExecution(@Param("runId") String runId, @Param("now") Instant now,
		@Param("staleBefore") Instant staleBefore);

	/**
	 * Heartbeat + step counter for the owning worker. Returns 0 when the run has been cancelled,
	 * completed, or deleted — the worker must stop instead of overwriting that state.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RunEntity r set r.stepCount = :stepCount, r.claimedAt = :now "
		+ "where r.id = :runId and r.status in (com.actbrow.actbrow.model.RunStatus.IN_PROGRESS, "
		+ "com.actbrow.actbrow.model.RunStatus.WAITING_FOR_CLIENT_TOOL)")
	int recordProgress(@Param("runId") String runId, @Param("stepCount") int stepCount, @Param("now") Instant now);

	/**
	 * Compare-and-set between two non-terminal statuses (e.g. IN_PROGRESS ↔ WAITING_FOR_CLIENT_TOOL).
	 * Returns 0 if the run is no longer in {@code from} — typically because it was cancelled.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RunEntity r set r.status = :to, r.claimedAt = :now where r.id = :runId and r.status = :from")
	int transition(@Param("runId") String runId, @Param("from") RunStatus from, @Param("to") RunStatus to,
		@Param("now") Instant now);

	/**
	 * Moves a run to a terminal status only if it is still active. Terminal states are final:
	 * a cancelled run can never later become COMPLETED or FAILED, and vice versa.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RunEntity r set r.status = :to, r.lastError = :lastError, r.completedAt = :now "
		+ "where r.id = :runId and r.status not in (com.actbrow.actbrow.model.RunStatus.COMPLETED, "
		+ "com.actbrow.actbrow.model.RunStatus.FAILED, com.actbrow.actbrow.model.RunStatus.CANCELLED)")
	int finishIfActive(@Param("runId") String runId, @Param("to") RunStatus to,
		@Param("lastError") String lastError, @Param("now") Instant now);

	/** Runs created but never picked up (their creator died before claiming). */
	List<RunEntity> findByStatusAndCreatedAtBefore(RunStatus status, Instant createdBefore);

	/** Runs whose owning worker stopped heartbeating (process crash / restart mid-run). */
	List<RunEntity> findByStatusInAndClaimedAtBefore(List<RunStatus> statuses, Instant claimedBefore);
}
