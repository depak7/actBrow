package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.WaitlistRequest;
import com.actbrow.actbrow.api.dto.WaitlistResponse;
import com.actbrow.actbrow.model.WaitlistEntry;
import com.actbrow.actbrow.repository.WaitlistRepository;

@Service
public class WaitlistService {

	private final WaitlistRepository waitlistRepository;
	private final SignupNotificationService signupNotificationService;

	public WaitlistService(WaitlistRepository waitlistRepository,
			SignupNotificationService signupNotificationService) {
		this.waitlistRepository = waitlistRepository;
		this.signupNotificationService = signupNotificationService;
	}

	/**
	 * Idempotent demo request: existing emails return success without a hard error,
	 * so hunters/re-submits don't hit a rough "already registered" alert.
	 */
	public WaitlistResponse joinWaitlist(WaitlistRequest request) {
		return waitlistRepository.findByEmail(request.email())
			.map(existing -> toResponse(existing, true))
			.orElseGet(() -> {
				WaitlistEntry entry = new WaitlistEntry();
				entry.setEmail(request.email());
				entry.setName(request.name());
				entry.setCompany(request.company());
				entry.setUseCase(request.useCase());

				WaitlistEntry saved = waitlistRepository.save(entry);
				signupNotificationService.notifyNewWaitlist(saved);
				return toResponse(saved, false);
			});
	}

	private WaitlistResponse toResponse(WaitlistEntry entry, boolean alreadyRegistered) {
		return new WaitlistResponse(
			entry.getId(),
			entry.getEmail(),
			entry.getName(),
			entry.getCompany(),
			entry.getCreatedAt(),
			alreadyRegistered
		);
	}
}
