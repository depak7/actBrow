package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.WaitlistEntry;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, String> {

	boolean existsByEmail(String email);

	Optional<WaitlistEntry> findByEmail(String email);
}
