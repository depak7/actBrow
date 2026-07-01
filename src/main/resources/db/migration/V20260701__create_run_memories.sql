CREATE TABLE IF NOT EXISTS run_memories (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    conversation_id VARCHAR(36) NOT NULL,
    objective TEXT,
    current_step_goal TEXT,
    success_criteria TEXT,
    known_entities_json TEXT,
    last_action_json TEXT,
    last_failures_json TEXT,
    blocked_reason VARCHAR(2000),
    summary_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_memories_conversation_id ON run_memories (conversation_id);
