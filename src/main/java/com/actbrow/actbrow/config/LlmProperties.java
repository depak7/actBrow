package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.llm")
public record LlmProperties(String defaultProvider) {
}
