package com.actbrow.actbrow.model;

/**
 * Explicit phase of a run's plan/act/verify cycle. Persisted in a run checkpoint so an interrupted
 * run can be resumed from its last durable point instead of restarting from raw conversation state.
 */
public enum RunPhase {
	PLANNING,
	EXECUTING,
	VERIFYING,
	NEEDS_CLARIFICATION,
	WAITING_FOR_CLIENT
}
