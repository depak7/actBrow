package com.actbrow.actbrow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunEntity;

public interface RunRepository extends JpaRepository<RunEntity, String> {
}
