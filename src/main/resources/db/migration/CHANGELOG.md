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

## Flyway notes

Flyway is enabled (`spring.flyway.enabled=true`, `baseline-on-migrate=true`, `baseline-version=0`).
`SchemaPatchRunner` was removed — additive schema must ship as versioned SQL here.
Core tables (users/assistants/tools/runs/…) are still assumed from the historical schema; a full greenfield baseline remains a follow-up.
