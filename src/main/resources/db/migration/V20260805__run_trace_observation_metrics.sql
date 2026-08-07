-- Structure-first observation metrics: count observe vs screenshot calls and
-- accumulate client-tool park wait so loop-layer latency is measurable per run.
ALTER TABLE run_traces ADD COLUMN IF NOT EXISTS observe_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE run_traces ADD COLUMN IF NOT EXISTS screenshot_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE run_traces ADD COLUMN IF NOT EXISTS client_tool_wait_ms BIGINT NOT NULL DEFAULT 0;
