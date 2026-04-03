package com.actbrow.actbrow.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.TenantRequest;
import com.actbrow.actbrow.api.dto.TenantResponse;
import com.actbrow.actbrow.service.TenantService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/tenants")
public class TenantController {

	private final TenantService tenantService;

	public TenantController(TenantService tenantService) {
		this.tenantService = tenantService;
	}

	@GetMapping
	public List<TenantResponse> listTenants() {
		return tenantService.list();
	}

	@GetMapping("/{tenantId}")
	public ResponseEntity<TenantResponse> getTenant(@PathVariable String tenantId) {
		return ResponseEntity.ok(tenantService.getTenant(tenantId));
	}

	@PostMapping
	public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody TenantRequest request) {
		return ResponseEntity.ok(tenantService.create(request));
	}

	@PutMapping("/{tenantId}")
	public ResponseEntity<TenantResponse> updateTenant(@PathVariable String tenantId,
		@Valid @RequestBody TenantRequest request) {
		return ResponseEntity.ok(tenantService.update(tenantId, request));
	}

	@DeleteMapping("/{tenantId}")
	public ResponseEntity<Void> deleteTenant(@PathVariable String tenantId) {
		tenantService.delete(tenantId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{tenantId}/regenerate-key")
	public ResponseEntity<TenantResponse> regenerateApiKey(@PathVariable String tenantId) {
		return ResponseEntity.ok(tenantService.regenerateApiKey(tenantId));
	}

	@PostMapping("/validate-key")
	public ResponseEntity<Map<String, Object>> validateApiKey(@RequestBody Map<String, String> payload) {
		String apiKey = payload.get("apiKey");
		if (apiKey == null || apiKey.isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("valid", false, "message", "API key is required"));
		}
		try {
			var tenant = tenantService.findByApiKey(apiKey);
			if (!tenant.isEnabled()) {
				return ResponseEntity.ok(Map.of("valid", false, "message", "Tenant is disabled"));
			}
			return ResponseEntity.ok(Map.of(
				"valid", true,
				"tenantId", tenant.getId(),
				"tenantKey", tenant.getKey()));
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.ok(Map.of("valid", false, "message", "Invalid API key"));
		}
	}
}
