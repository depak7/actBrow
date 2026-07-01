# Agent Harness TODO

This file tracks the remaining implementation phases after Phase 2 of the prod-grade agent harness refactor.

Current status:
- Phase 1 completed: persistent run memory + layered context assembly
- Phase 2 completed: planner / executor / verifier split in runtime orchestration
- Phase 3 completed: failure taxonomy + deterministic policy engine
- Phase 4 completed: checkpoint + resume (RunPhase, RunCheckpointService, run_checkpoints)
- Phase 5 completed: eval trace recorder (EvalTraceRecorder, run_traces) + recovery benchmark tests
- Phase 6 completed: safer tool contracts (ToolContract, SideEffectLevel, write post-verification)
- Phase 7 completed: production controls (FeatureFlagService, ToolCircuitBreaker, AuditLogService, shadow mode)

All 7 phases implemented. Remaining hardening is behavioral depth (full checkpoint replay,
persistent audit store, per-tenant flag storage) rather than new structure — see notes per phase.

DB rollout note:
- `run_memories` must exist before deploying the Phase 1/2 runtime changes.
- Apply `src/main/resources/db/migration/V20260701__create_run_memories.sql` in production.
- Phase 3 adds `V20260701_1__add_conversation_message_seq.sql` (nullable `seq` on
  `conversation_messages` for deterministic message ordering). Apply before deploying the
  Phase 3 runtime changes; existing rows are unaffected (no backfill required).

## Phase 3: Failure Taxonomy And Policy Engine — DONE

Goal:
- Turn verifier outcomes and tool failures into deterministic recovery policies instead of relying on prompt behavior alone.

Delivered:
- `FailureType` enum classifies tool outcomes (auth, rate-limited, timeout, invalid-args, not-found,
  conflict, server-error, tool-exhausted, client-incomplete, unknown, none).
- `FailureClassifier` is the single source of truth for classification.
- `RunVerifier` now derives its status from `FailureClassifier` and carries `failureType` on
  `VerificationDecision` (keyword logic consolidated into the classifier).
- `RunPolicyEngine` maps each failure class to a deterministic `PolicyAction`
  (retry / switch tool / ask clarification / require user intervention / stop).
- `RunService` records a `POLICY_DECISION` trace step (new `RunStepType`) after each verify.
- Tests: `FailureClassifierTests`, `RunPolicyEngineTests` (plus existing `RunVerifierTests` retained).

Acceptance criteria (met):
- Known failure modes classify consistently across runs (pure, table-tested).
- Recovery is code-driven: policy is chosen from the structured failure type, not prompt behavior.
- Tool exhaustion, auth errors, and rate limits take different recovery paths.

## Phase 4: Checkpoint And Resume

Goal:
- Make long-running or interrupted runs resumable.

Deliverables:
- Introduce explicit run phase tracking:
  - `PLANNING`
  - `EXECUTING`
  - `VERIFYING`
  - `NEEDS_CLARIFICATION`
  - `WAITING_FOR_CLIENT`
- Persist planner output, last execution attempt, and verifier result.
- Resume runs after process restart or pending-tool interruption.
- Avoid restarting the entire run from raw conversation state.

Suggested files:
- `src/main/java/com/actbrow/actbrow/model/RunPhase.java`
- `src/main/java/com/actbrow/actbrow/service/RunCheckpointService.java`

Possible DB changes:
- likely add checkpoint fields to `runs` or a separate `run_checkpoints` table

Acceptance criteria:
- Interrupted runs can continue from the last durable checkpoint.
- Client-tool waits do not force full run restart.

## Phase 5: Eval Trace Recorder

Goal:
- Capture enough structured data to evaluate and improve the harness safely.

Deliverables:
- Add persistent trace recording for:
  - prompt version
  - toolset version
  - planning outcome
  - execution attempts
  - verifier decisions
  - final outcome
  - latency and tool counts
- Add a simple trace query API or internal service for debugging.
- Add benchmark scenarios for happy path and failure recovery.

Suggested files:
- `src/main/java/com/actbrow/actbrow/service/EvalTraceRecorder.java`
- `src/main/java/com/actbrow/actbrow/model/RunTraceEntity.java`
- `src/main/java/com/actbrow/actbrow/repository/RunTraceRepository.java`

Possible DB changes:
- `run_traces` table

Acceptance criteria:
- A completed run can be inspected as a full plan/act/verify trace.
- Recovery success rate and false completion rate become measurable.

## Phase 6: Safer Tool Contracts

Goal:
- Make tool behavior predictable enough for production writes and external integrations.

Deliverables:
- Extend tool metadata with:
  - `retryable`
  - `idempotent`
  - `sideEffectLevel`
  - `verificationTool`
  - `verificationMode`
  - `preconditions`
  - `commonFailureModes`
- Differentiate read tools from write tools in policy handling.
- Add post-action verification requirements for write actions.

Suggested files:
- extend existing tool metadata handling in:
  - `ToolService`
  - `ToolCatalogPolicies`
  - `RunPolicyEngine`
  - `RunVerifier`

Acceptance criteria:
- Writes require stricter validation and verification than reads.
- Tool metadata can directly drive policy decisions.

## Phase 7: Production Controls

Goal:
- Make rollout safe across tenants and noisy integrations.

Deliverables:
- Add per-tenant or per-assistant feature flags.
- Add circuit breakers for unhealthy tools.
- Add audit logging for all tool attempts.
- Add escalation path when the agent is blocked repeatedly.
- Add shadow or observe-only mode for risky actions.

Suggested files:
- `src/main/java/com/actbrow/actbrow/service/FeatureFlagService.java`
- `src/main/java/com/actbrow/actbrow/service/ToolCircuitBreaker.java`
- `src/main/java/com/actbrow/actbrow/service/AuditLogService.java`

Possible DB changes:
- feature flag storage and/or audit log tables

Acceptance criteria:
- New customers can launch with restricted capabilities.
- Failing tools can be automatically isolated without taking down the whole agent.

## Cross-Cutting Improvements

Items to apply while implementing later phases:
- Add prompt versioning to runtime records.
- Add tool catalog versioning or checksum to traces.
- Add structured `VERIFIER_DECISION` payloads instead of relying on `toString()`.
- Add explicit `PLANNER_DECISION` and `POLICY_DECISION` trace payloads.
- Tighten test coverage around:
  - multi-tool batches
  - replanning after verifier failure
  - client-tool timeout recovery
  - auth and permission failures
  - destructive-action verification

## Recommended Execution Order

1. Phase 3: Failure Taxonomy And Policy Engine
2. Phase 4: Checkpoint And Resume
3. Phase 5: Eval Trace Recorder
4. Phase 6: Safer Tool Contracts
5. Phase 7: Production Controls

## Deployment Reminder

Before deploying the current runtime:
- run the `run_memories` migration manually
- verify the target database contains the `run_memories` table and index
- then deploy the application changes
