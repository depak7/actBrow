package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record WaitlistRequest(
	@NotBlank @Email String email,
	@NotBlank String name,
	String company,
	String useCase
) {
}
