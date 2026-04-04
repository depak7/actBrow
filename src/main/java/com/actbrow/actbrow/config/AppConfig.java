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
	GeminiProperties.class,
	GoogleOAuthProperties.class,
	GroqProperties.class,
	LlmProperties.class,
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
		configuration.setAllowedOrigins(corsProperties.allowedOrigins());
		configuration.setAllowedHeaders(Arrays.asList(
			"Origin",
			"Content-Type",
			"Accept",
			"Authorization",
			"X-API-Key",
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
