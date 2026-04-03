package com.actbrow.actbrow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.WaitlistEntry;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, String> {

	boolean existsByEmail(String email);
}
