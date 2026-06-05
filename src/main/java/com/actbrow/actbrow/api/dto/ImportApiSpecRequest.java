package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to import a Swagger/OpenAPI spec and generate browser-executed HTTP tools.
 *
 * @param name            integration name; tools are namespaced under it and re-importing the
 *                        same name updates the existing integration
 * @param specContent     raw spec, JSON or YAML, Swagger 2.0 or OpenAPI 3.x
 * @param baseUrlOverride overrides the spec's {@code servers[0].url} when set
 * @param allowCrossOrigin whether the browser may call a different origin than the embed page
 *                        (defaults to true)
 */
public record ImportApiSpecRequest(
	@NotBlank @Size(max = 255) String name,
	@NotBlank @Size(max = 4_000_000) String specContent,
	String baseUrlOverride,
	Boolean allowCrossOrigin
) {
}
