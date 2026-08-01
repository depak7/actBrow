package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit logging for tool attempts and escalations (Phase 7). Every tool attempt and its outcome is
 * logged (structured, on a dedicated logger) and kept in a bounded in-memory ring for quick
 * inspection. Escalations (agent repeatedly blocked) are logged at WARN.
 */
@Service
public class AuditLogService {

	private static final Logger audit = LoggerFactory.getLogger("actbrow.audit");
	private static final int RING_CAPACITY = 500;

	public record AuditEntry(Instant at, String runId, String assistantId, String toolKey, String event,
		String detail) {
	}

	private final Deque<AuditEntry> recent = new ConcurrentLinkedDeque<>();

	public void toolAttempt(String runId, String assistantId, String toolKey) {
		add(new AuditEntry(Instant.now(), runId, assistantId, toolKey, "tool_attempt", null));
		audit.info("tool_attempt run={} assistant={} tool={}", runId, assistantId, toolKey);
	}

	public void toolOutcome(String runId, String assistantId, String toolKey, boolean success, String detail) {
		String event = success ? "tool_success" : "tool_failure";
		add(new AuditEntry(Instant.now(), runId, assistantId, toolKey, event, detail));
		audit.info("{} run={} assistant={} tool={} detail={}", event, runId, assistantId, toolKey, detail);
	}

	public void circuitOpen(String runId, String assistantId, String toolKey) {
		add(new AuditEntry(Instant.now(), runId, assistantId, toolKey, "circuit_open", null));
		audit.warn("circuit_open run={} assistant={} tool={} — tool isolated", runId, assistantId, toolKey);
	}

	public void escalation(String runId, String assistantId, String detail) {
		add(new AuditEntry(Instant.now(), runId, assistantId, null, "escalation", detail));
		audit.warn("escalation run={} assistant={} detail={}", runId, assistantId, detail);
	}

	public void shadowSkip(String runId, String assistantId, String toolKey) {
		add(new AuditEntry(Instant.now(), runId, assistantId, toolKey, "shadow_skip", null));
		audit.info("shadow_skip run={} assistant={} tool={} — write not executed (observe-only)",
			runId, assistantId, toolKey);
	}

	/**
	 * Records an operator flipping a runtime safety control. Logged at WARN because a changed kill
	 * switch or shadow mode is the single most likely explanation for "the agent stopped doing
	 * anything" — whoever debugs that next needs to find it without asking around.
	 */
	public void safetyFlagChanged(String assistantId, String flag, boolean enabled, String actorUserId) {
		add(new AuditEntry(Instant.now(), null, assistantId, null, "safety_flag_changed",
			flag + "=" + enabled + " by=" + actorUserId));
		audit.warn("safety_flag_changed assistant={} flag={} value={} actor={}", assistantId, flag, enabled,
			actorUserId);
	}

	/** Records an operator force-closing a tool circuit ahead of its cooldown. */
	public void circuitReset(String assistantId, String toolKey, String actorUserId) {
		add(new AuditEntry(Instant.now(), null, assistantId, toolKey, "circuit_reset", "by=" + actorUserId));
		audit.warn("circuit_reset assistant={} tool={} actor={}", assistantId, toolKey, actorUserId);
	}

	public List<AuditEntry> recentEntries() {
		return new ArrayList<>(recent);
	}

	private void add(AuditEntry entry) {
		recent.addLast(entry);
		while (recent.size() > RING_CAPACITY) {
			recent.pollFirst();
		}
	}
}
