package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record ConversationMessageResponse(String id, String role, String content, Instant createdAt) {
}
