package com.actbrow.actbrow.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.agent")
public record ActbrowProperties(int maxSteps, Duration toolTimeout) {
}
