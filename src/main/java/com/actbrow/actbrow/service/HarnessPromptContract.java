package com.actbrow.actbrow.service;

/**
 * Prompt fragments for the in-app agent harness, distilled from production harness practice
 * (bounded-efficiency turns, evidence-first grounding, recoverable tool failures, verify-before-done).
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Bounded efficiency</b> — scope + done-when + stop condition beat "think deeply" / multi-plan thrash</li>
 *   <li><b>Evidence over speculation</b> — PAGE_CONTEXT and successful tool results outrank prose guesses</li>
 *   <li><b>Recoverable failures</b> — every bad tool path names a next valid action</li>
 *   <li><b>Modular load</b> — keep the base prompt short; step-specific rules live in runtime guidance</li>
 * </ul>
 *
 * <p>These strings are deterministic harness constraints (what prompts can only request, code can
 * only partially enforce). Prefer tightening tool results and policy over growing this forever.
 */
public final class HarnessPromptContract {

	private HarnessPromptContract() {
	}

	/**
	 * Compact turn contract injected every planner step (cheap, high leverage). Complements the longer
	 * assistant system prompt without repeating every hard rule.
	 */
	public static String turnEfficiencyContract() {
		return """
			TURN CONTRACT (bounded efficiency — follow every turn):
			  SCOPE: Solve only the latest user ask. Ignore stale tool failures unless the user continues that task.
			  PLAN: Prefer one shortest path. Do NOT brainstorm multiple approaches or "think deeply" for its own sake.
			  EVIDENCE: Trust PAGE_CONTEXT and successful tool results over user prose about what is on screen.
			  DONE-WHEN (binary): You can answer or act from evidence you have — then stop with a final answer.
			  STOP: After at most one observation tool and any needed action tools for this ask, produce a final answer.
			  UNCLEAR: If the latest ask is ambiguous, ask ONE clarifying question (with OPTIONS when choices exist). Do not guess.
			  TOOLS: Only schema names (or tool.search keys). Never invent tools, paths, or on-screen facts.

			""".stripIndent();
	}

	/** Evidence hierarchy for context assembly — model should prefer tool truth over chat history. */
	public static String evidencePriorityRules() {
		return """
			EVIDENCE PRIORITY (highest wins):
			  1. Latest successful tool result in this run (and PAGE_CONTEXT on the latest user message).
			  2. Working memory entities / last successful action.
			  3. Recent conversation — only when 1–2 do not cover the ask.
			Never invent details missing from (1). If (1) failed or timed out, you have NO observation from that call.
			If the user claims something about the page that contradicts (1), trust (1) and say what you actually see.

			""".stripIndent();
	}

	/** Short recovery recipe shape tools already echo; keep the planner aligned. */
	public static String failureRecoveryRules() {
		return """
			ON TOOL FAILURE (recoverable by default):
			  1. What failed (tool + error) — state it honestly.
			  2. What is still safe (do not claim page/API data you did not receive).
			  3. Next valid action: fix arguments, different tool, tool.search, one clarification, or final answer.
			Never retry the exact same tool+arguments. Never invent a successful result.

			""".stripIndent();
	}

	/**
	 * Muse/Cline-style completion discipline adapted for in-app assistants: do not declare victory
	 * without evidence when an action tool was used.
	 */
	public static String verifyBeforeDoneRules() {
		return """
			VERIFY BEFORE DONE:
			  - After a write/API/navigate tool, base the final answer on that tool's result (success or failure text).
			  - Do not claim "done" if the tool returned success=false or timed out.
			  - For navigation: use the navigate result's pageObserve / path — do not re-observe unless missing.
			  - For multi-step tours: one navigation per user message, then stop with OPTIONS to continue.

			""".stripIndent();
	}

	/** Provider-level prefix when building the model system prompt (kept minimal). */
	public static String modelProviderHarnessPrefix() {
		return """
			Harness constraints: use only declared function names; never invent tools. Prefer a concise final answer \
			when tools are unnecessary. After a tool result, continue toward DONE-WHEN — do not restate failures \
			unless the latest user turn continues that task. If ambiguous, ask one clarification instead of guessing.
			""".stripIndent();
	}
}
