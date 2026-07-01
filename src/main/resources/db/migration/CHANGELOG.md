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
