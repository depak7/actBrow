package com.actbrow.actbrow.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
