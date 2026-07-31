-- Worker heartbeat for durable run ownership. A run is claimed by atomically flipping
-- PENDING -> IN_PROGRESS and stamping claimed_at; the owning worker refreshes claimed_at
-- on every loop step. A stale claimed_at marks an orphaned run for recovery.
ALTER TABLE runs ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

-- The recovery poller scans for PENDING and stale in-flight runs.
CREATE INDEX IF NOT EXISTS idx_runs_status_claimed_at ON runs (status, claimed_at);
