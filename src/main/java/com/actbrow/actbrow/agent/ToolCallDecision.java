package com.actbrow.actbrow.agent;

public record ToolCallDecision(String summary, ToolCall toolCall) implements ModelDecision {
}
