-- MCP server connections (per assistant) + widget theme JSON on assistants.
-- Safe to re-run on Postgres (IF NOT EXISTS). Apply manually if Flyway is not enabled.

ALTER TABLE assistants ADD COLUMN IF NOT EXISTS widget_theme_json TEXT;

CREATE TABLE IF NOT EXISTS mcp_servers (
	id VARCHAR(36) PRIMARY KEY,
	assistant_id VARCHAR(255) NOT NULL,
	name VARCHAR(255) NOT NULL,
	server_url VARCHAR(2000) NOT NULL,
	auth_headers_json TEXT,
	enabled BOOLEAN NOT NULL DEFAULT TRUE,
	tool_keys_json TEXT,
	last_synced_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL,
	updated_at TIMESTAMPTZ,
	CONSTRAINT uk_mcp_server_assistant_name UNIQUE (assistant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_mcp_servers_assistant_id ON mcp_servers (assistant_id);
