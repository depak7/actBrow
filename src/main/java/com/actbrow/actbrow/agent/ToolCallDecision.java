package com.actbrow.actbrow.agent;

import java.util.List;

public record ToolCallDecision(String summary, List<ToolCall> toolCalls) implements ModelDecision {
}
