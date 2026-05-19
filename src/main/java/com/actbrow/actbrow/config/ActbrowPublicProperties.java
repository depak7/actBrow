package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.public")
public record ActbrowPublicProperties(String baseUrl) {
}
