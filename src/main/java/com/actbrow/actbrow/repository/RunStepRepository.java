package com.actbrow.actbrow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunStepEntity;

public interface RunStepRepository extends JpaRepository<RunStepEntity, String> {

	List<RunStepEntity> findAllByRunIdOrderByStepIndexAsc(String runId);
}
