package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record WaitlistResponse(
	String id,
	String email,
	String name,
	String company,
	Instant createdAt
) {
}
