-- Durable per-assistant safety flags (kill switch, shadow mode).
-- Before this table the dashboard toggles lived only in process memory, so a restart or deploy
-- silently reverted an operator's kill switch. An assistant with no row keeps the configured
-- baseline (actbrow.flags.*). Safe to re-run on Postgres (IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS assistant_safety_flags (
	id VARCHAR(36) PRIMARY KEY,
	assistant_id VARCHAR(255) NOT NULL,
	flag VARCHAR(64) NOT NULL,
	enabled BOOLEAN NOT NULL,
	updated_by VARCHAR(255),
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT uk_assistant_safety_flag UNIQUE (assistant_id, flag)
);

CREATE INDEX IF NOT EXISTS idx_assistant_safety_flags_assistant_id ON assistant_safety_flags (assistant_id);
