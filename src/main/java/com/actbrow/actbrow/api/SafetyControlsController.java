package com.actbrow.actbrow.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.CircuitResponse;
import com.actbrow.actbrow.api.dto.SafetyStatusResponse;
import com.actbrow.actbrow.api.dto.UpdateSafetyRequest;
import com.actbrow.actbrow.service.AuditLogService;
import com.actbrow.actbrow.service.FeatureFlagService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;
import com.actbrow.actbrow.service.ToolCircuitBreaker;

import jakarta.validation.Valid;

/**
 * Operator view and controls for the runtime safety switches (kill switch, shadow mode, tool circuit
 * breakers). These exist so an incident can be handled from the UI instead of by hand-crafting curl
 * against an internal service.
 *
 * <p>The kill switch and shadow mode set here are persisted per assistant
 * ({@code assistant_safety_flags}) and survive restarts and deploys. Circuit breaker state is still
 * in memory: circuits start closed after a restart. The environment variables
 * {@code ACTBROW_TOOLS_ENABLED} / {@code ACTBROW_SHADOW_MODE} set the baseline for assistants with no
 * override of their own.
 *
 * <p>All endpoints are account-scoped: the assistant must belong to the calling user.
 */
@RestController
@Validated
@RequestMapping("/v1/assistants/{assistantId}/safety")
public class SafetyControlsController {

	private final FeatureFlagService featureFlagService;
	private final ToolCircuitBreaker toolCircuitBreaker;
	private final AuditLogService auditLogService;
	private final ResourceAuthorizationService authorizationService;

	public SafetyControlsController(FeatureFlagService featureFlagService, ToolCircuitBreaker toolCircuitBreaker,
		AuditLogService auditLogService, ResourceAuthorizationService authorizationService) {
		this.featureFlagService = featureFlagService;
		this.toolCircuitBreaker = toolCircuitBreaker;
		this.auditLogService = auditLogService;
		this.authorizationService = authorizationService;
	}

	@GetMapping
	public SafetyStatusResponse status(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return snapshot(assistantId);
	}

	/**
	 * Applies only the fields present in the body — a null field leaves that control untouched, so an
	 * operator can flip the kill switch without accidentally resetting shadow mode. Each applied
	 * change is audited with the acting user.
	 */
	@PutMapping
	public SafetyStatusResponse update(@PathVariable String assistantId,
		@Valid @RequestBody UpdateSafetyRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		if (request.toolsEnabled() != null) {
			featureFlagService.setAssistantFlag(assistantId, FeatureFlagService.TOOLS_ENABLED,
				request.toolsEnabled(), userId);
			auditLogService.safetyFlagChanged(assistantId, FeatureFlagService.TOOLS_ENABLED,
				request.toolsEnabled(), userId);
		}
		if (request.shadowMode() != null) {
			featureFlagService.setAssistantFlag(assistantId, FeatureFlagService.SHADOW_MODE, request.shadowMode(),
				userId);
			auditLogService.safetyFlagChanged(assistantId, FeatureFlagService.SHADOW_MODE, request.shadowMode(),
				userId);
		}
		return snapshot(assistantId);
	}

	/**
	 * Force-closes one tool's circuit so a fixed tool can be retried immediately instead of waiting
	 * out the cooldown. Resetting a tool that was never tripped is a no-op and still returns 200 —
	 * the caller's intent ("this circuit should be closed") is satisfied either way.
	 */
	@PostMapping("/circuits/{toolKey}/reset")
	public SafetyStatusResponse resetCircuit(@PathVariable String assistantId, @PathVariable String toolKey,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		toolCircuitBreaker.reset(circuitKey(assistantId, toolKey));
		auditLogService.circuitReset(assistantId, toolKey, userId);
		return snapshot(assistantId);
	}

	private SafetyStatusResponse snapshot(String assistantId) {
		Map<String, Boolean> circuits = toolCircuitBreaker.snapshotFor(assistantId);
		List<CircuitResponse> responses = new ArrayList<>(circuits.size());
		circuits.forEach((toolKey, open) -> responses.add(new CircuitResponse(toolKey, open)));
		// Open circuits first, then alphabetical — during an incident the thing that is broken should
		// be at the top of the list without the UI having to sort.
		responses.sort(Comparator.comparing(CircuitResponse::open).reversed()
			.thenComparing(CircuitResponse::toolKey));
		return new SafetyStatusResponse(assistantId,
			featureFlagService.isEnabled(assistantId, FeatureFlagService.TOOLS_ENABLED),
			featureFlagService.isEnabled(assistantId, FeatureFlagService.SHADOW_MODE),
			responses);
	}

	/** Mirrors the composite key {@code RunService} uses so operator actions hit the same circuits. */
	private static String circuitKey(String assistantId, String toolKey) {
		return assistantId + "|" + toolKey;
	}
}
