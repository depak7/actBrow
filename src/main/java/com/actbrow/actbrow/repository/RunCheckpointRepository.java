package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.model.RunCheckpointEntity;
import com.actbrow.actbrow.model.RunPhase;

public interface RunCheckpointRepository extends JpaRepository<RunCheckpointEntity, String> {

	Optional<RunCheckpointEntity> findByRunId(String runId);

	/**
	 * Phase-only update in a single statement, for the common case where the checkpoint row already
	 * exists. {@code recordPhase} runs three times per step/tool cycle, so the read-then-write it used
	 * to do was doubling the query count on the hottest write path.
	 *
	 * @return 0 when no checkpoint exists yet — the caller must then insert one.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RunCheckpointEntity c set c.phase = :phase, c.stepIndex = :stepIndex, "
		+ "c.conversationId = :conversationId where c.runId = :runId")
	int updatePhase(@Param("runId") String runId, @Param("conversationId") String conversationId,
		@Param("phase") RunPhase phase, @Param("stepIndex") int stepIndex);

	void deleteByRunId(String runId);
}
