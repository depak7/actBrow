package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.google.oauth")
public record GoogleOAuthProperties(String clientId) {
}
