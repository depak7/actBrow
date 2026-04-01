package com.actbrow.actbrow.agent;

public sealed interface ModelDecision permits FinalResponseDecision, ToolCallDecision {
}
