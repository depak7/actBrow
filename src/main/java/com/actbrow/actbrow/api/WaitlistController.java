package com.actbrow.actbrow.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.WaitlistRequest;
import com.actbrow.actbrow.api.dto.WaitlistResponse;
import com.actbrow.actbrow.service.WaitlistService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/waitlist")
public class WaitlistController {

	private final WaitlistService waitlistService;

	public WaitlistController(WaitlistService waitlistService) {
		this.waitlistService = waitlistService;
	}

	@PostMapping
	public ResponseEntity<WaitlistResponse> joinWaitlist(@Valid @RequestBody WaitlistRequest request) {
		WaitlistResponse response = waitlistService.joinWaitlist(request);
		return ResponseEntity.ok(response);
	}
}
