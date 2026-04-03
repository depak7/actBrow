package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.WaitlistRequest;
import com.actbrow.actbrow.api.dto.WaitlistResponse;
import com.actbrow.actbrow.model.WaitlistEntry;
import com.actbrow.actbrow.repository.WaitlistRepository;

@Service
public class WaitlistService {

	private final WaitlistRepository waitlistRepository;

	public WaitlistService(WaitlistRepository waitlistRepository) {
		this.waitlistRepository = waitlistRepository;
	}

	public WaitlistResponse joinWaitlist(WaitlistRequest request) {
		if (waitlistRepository.existsByEmail(request.email())) {
			throw new IllegalArgumentException("Email already registered");
		}

		WaitlistEntry entry = new WaitlistEntry();
		entry.setEmail(request.email());
		entry.setName(request.name());
		entry.setCompany(request.company());
		entry.setUseCase(request.useCase());
		
		WaitlistEntry saved = waitlistRepository.save(entry);
		return toResponse(saved);
	}

	private WaitlistResponse toResponse(WaitlistEntry entry) {
		return new WaitlistResponse(
			entry.getId(),
			entry.getEmail(),
			entry.getName(),
			entry.getCompany(),
			entry.getCreatedAt()
		);
	}
}
