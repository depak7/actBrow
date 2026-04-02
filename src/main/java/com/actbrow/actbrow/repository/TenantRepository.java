package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.TenantEntity;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {

	Optional<TenantEntity> findByKey(String key);

	Optional<TenantEntity> findByApiKey(String apiKey);

	Optional<TenantEntity> findByUserId(String userId);

	boolean existsByUserId(String userId);

	List<TenantEntity> findAllByUserId(String userId);
}
