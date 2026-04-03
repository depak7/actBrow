package com.actbrow.actbrow.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.TenantRequest;
import com.actbrow.actbrow.api.dto.TenantResponse;
import com.actbrow.actbrow.model.TenantEntity;
import com.actbrow.actbrow.repository.TenantRepository;

@Service
public class TenantService {

	private final TenantRepository tenantRepository;

	public TenantService(TenantRepository tenantRepository) {
		this.tenantRepository = tenantRepository;
	}

	public TenantResponse create(TenantRequest request) {
		tenantRepository.findByKey(request.key()).ifPresent(existing -> {
			throw new IllegalArgumentException("Tenant key already exists");
		});

		TenantEntity entity = new TenantEntity();
		entity.setKey(request.key());
		entity.setName(request.name());
		entity.setApiKey(generateApiKey(request.apiKey()));
		entity.setEnabled(request.enabled());
		return toResponse(tenantRepository.save(entity));
	}

	public TenantResponse update(String tenantId, TenantRequest request) {
		TenantEntity entity = requireEntity(tenantId);

		if (!entity.getKey().equals(request.key())) {
			tenantRepository.findByKey(request.key()).ifPresent(existing -> {
				throw new IllegalArgumentException("Tenant key already exists");
			});
		}

		entity.setKey(request.key());
		entity.setName(request.name());
		if (request.apiKey() != null && !request.apiKey().isBlank()) {
			entity.setApiKey(generateApiKey(request.apiKey()));
		}
		entity.setEnabled(request.enabled());
		return toResponse(tenantRepository.save(entity));
	}

	public TenantResponse regenerateApiKey(String tenantId) {
		TenantEntity entity = requireEntity(tenantId);
		entity.setApiKey(generateApiKey(null));
		return toResponse(tenantRepository.save(entity));
	}

	public List<TenantResponse> list() {
		return tenantRepository.findAll().stream().map(this::toResponse).toList();
	}

	public TenantResponse getTenant(String tenantId) {
		return toResponse(requireEntity(tenantId));
	}

	public TenantEntity requireEntity(String tenantId) {
		return tenantRepository.findById(tenantId)
			.orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
	}

	public TenantEntity findByApiKey(String apiKey) {
		return tenantRepository.findByApiKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
	}

	public void delete(String tenantId) {
		TenantEntity entity = requireEntity(tenantId);
		tenantRepository.delete(entity);
	}

	private String generateApiKey(String customKey) {
		if (customKey != null && !customKey.isBlank()) {
			return customKey;
		}
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public TenantResponse toResponse(TenantEntity entity) {
		return new TenantResponse(entity.getId(), entity.getKey(), entity.getName(),
			entity.getApiKey(), entity.isEnabled(), entity.getCreatedAt());
	}
}
