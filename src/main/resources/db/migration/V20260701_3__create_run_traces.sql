-- Phase 5: structured evaluation trace per completed run.
CREATE TABLE IF NOT EXISTS run_traces (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    conversation_id VARCHAR(36) NOT NULL,
    assistant_id VARCHAR(36) NOT NULL,
    prompt_version VARCHAR(64),
    toolset_version VARCHAR(64),
    planning_outcomes TEXT,
    verifier_decisions TEXT,
    execution_attempts INTEGER,
    tool_call_count INTEGER,
    final_outcome VARCHAR(64),
    latency_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_traces_assistant_id ON run_traces (assistant_id);
