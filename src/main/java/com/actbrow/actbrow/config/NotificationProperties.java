package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.notifications")
public record NotificationProperties(boolean signupEnabled, String signupRecipient, String from) {
}
