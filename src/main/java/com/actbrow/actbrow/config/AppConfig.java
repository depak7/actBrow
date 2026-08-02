package com.actbrow.actbrow.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties({
	ActbrowProperties.class,
	ActbrowSnapshotProperties.class,
	ActbrowPublicProperties.class,
	GoogleOAuthProperties.class,
	CorsProperties.class
})
public class AppConfig {

	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	CorsWebFilter corsWebFilter(CorsProperties corsProperties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowCredentials(true);
		// An embeddable widget is installed on customer domains we cannot enumerate centrally, so the
		// browser-level filter is permissive by default. The actual tenant boundary is enforced in
		// ApiKeyAuthFilter, which rejects a widget key used from an origin the assistant has not
		// listed — CORS is a browser convention, not an access control.
		//
		// setAllowedOriginPatterns rather than setAllowedOrigins: the spec forbids a literal "*" on a
		// credentialed response, and Spring turns that combination into a 500 on every preflight
		// rather than a browser-side error. Patterns echo the caller's own origin back instead.
		List<String> configured = corsProperties.allowedOrigins() == null
			? List.of()
			: corsProperties.allowedOrigins();
		if (configured.isEmpty() || configured.contains("*")) {
			configuration.setAllowedOriginPatterns(List.of("*"));
		}
		else {
			configuration.setAllowedOrigins(configured);
		}
		configuration.setAllowedHeaders(Arrays.asList(
			"Origin",
			"Content-Type",
			"Accept",
			"Authorization",
			"X-API-Key",
			"X-Actbrow-Auth-Type",
			"X-Actbrow-Assistant-Id",
			"X-User-Id",
			"X-Requested-With"
		));
		configuration.setAllowedMethods(Arrays.asList(
			"GET",
			"POST",
			"PUT",
			"DELETE",
			"OPTIONS",
			"PATCH"
		));
		configuration.setExposedHeaders(Arrays.asList(
			"Access-Control-Allow-Origin",
			"Access-Control-Allow-Credentials",
			"Content-Type"
		));
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return new CorsWebFilter(source);
	}
}
