-- Phase 4: durable run checkpoints for resume after interruption/restart.
CREATE TABLE IF NOT EXISTS run_checkpoints (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    conversation_id VARCHAR(36) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    step_index INTEGER NOT NULL,
    planner_outcome_json TEXT,
    last_execution_json TEXT,
    verifier_result_json TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_checkpoints_conversation_id ON run_checkpoints (conversation_id);
