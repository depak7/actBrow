# DB Changes

- `V20260701__create_run_memories.sql`
  - Creates `run_memories`
  - Adds `idx_run_memories_conversation_id`
- `V20260701_1__add_conversation_message_seq.sql`
  - Adds nullable `seq BIGINT` to `conversation_messages` (deterministic tiebreaker for message ordering)
- `V20260701_2__create_run_checkpoints.sql`
  - Creates `run_checkpoints` (Phase 4: resume after interruption)
- `V20260701_3__create_run_traces.sql`
  - Creates `run_traces` (Phase 5: eval trace recorder)
- `V20260701_4__fix_run_steps_type_check.sql`
  - Recreates `run_steps_type_check` to include VERIFIER_DECISION + POLICY_DECISION
- `V20260731__mcp_servers_and_widget_theme.sql`
  - Adds `assistants.widget_theme_json`
  - Creates `mcp_servers` for per-assistant MCP connections
- `V20260731_1__run_claiming.sql`
  - Adds `runs.claimed_at` (worker heartbeat for atomic run claiming / orphan recovery)
  - Adds `idx_runs_status_claimed_at` for the recovery poller
- `V20260801__hot_path_indexes.sql`
  - Adds 11 indexes for the agent hot path and dashboard reads. The historical (pre-Flyway) tables
    only ever got primary-key and unique-column indexes from Hibernate, so every non-unique foreign
    key was unindexed — including `conversation_messages(conversation_id, created_at, seq)`, which is
    read on every planning step. Measured on a 40k-row local table: 2864 → 210 shared buffer reads.
  - All statements are `IF NOT EXISTS`, so this is a no-op where an environment already has them.

- `V20260805__run_trace_observation_metrics.sql`
  - Adds `run_traces.observe_count`, `screenshot_count`, `client_tool_wait_ms` for structure-first
    observation telemetry (loop-layer latency outside the model API).

## Flyway notes

Flyway is enabled (`spring.flyway.enabled=true`, `baseline-on-migrate=true`, `baseline-version=0`).
`SchemaPatchRunner` was removed — additive schema must ship as versioned SQL here.
Core tables (users/assistants/tools/runs/…) are still assumed from the historical schema; a full greenfield baseline remains a follow-up.
