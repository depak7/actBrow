-- Compatibility baseline for installations that were created after Hibernate DDL was disabled.
--
-- The original core tables predate Flyway, but a new database has none of them. This migration
-- deliberately sorts after V20260701__create_run_memories and before V20260701_1__add_...
-- so an already-deployed database at version 20260701 can repair itself before the first
-- dependent ALTER TABLE runs. Every statement is idempotent: existing production tables and
-- their data are left untouched.

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255),
    profile_picture_url VARCHAR(255),
    google_id VARCHAR(255) NOT NULL UNIQUE,
    api_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS assistants (
    id VARCHAR(255) PRIMARY KEY,
    assistant_key VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    system_prompt VARCHAR(4000),
    model VARCHAR(255) NOT NULL,
    use_predefined_flows BOOLEAN NOT NULL,
    api_key VARCHAR(255) NOT NULL UNIQUE,
    setup_key VARCHAR(255) UNIQUE,
    widget_key VARCHAR(255) UNIQUE,
    allowed_origins_json TEXT,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    last_sync_summary_json TEXT,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS tools (
    id VARCHAR(255) PRIMARY KEY,
    tool_key VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    input_schema TEXT NOT NULL,
    output_schema TEXT,
    type VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    executor_ref VARCHAR(2000),
    default_arguments TEXT,
    metadata TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS assistant_tool_bindings (
    id VARCHAR(255) PRIMARY KEY,
    assistant_id VARCHAR(255) NOT NULL,
    tool_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(255) PRIMARY KEY,
    assistant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_messages (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    tool_call_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    assistant_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    step_count INTEGER NOT NULL,
    last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS run_steps (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    step_index INTEGER NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS navigation_flows (
    id VARCHAR(255) PRIMARY KEY,
    assistant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    trigger_phrase VARCHAR(255) NOT NULL,
    steps_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(255) PRIMARY KEY,
    assistant_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content VARCHAR(32000) NOT NULL,
    source VARCHAR(255),
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS api_integrations (
    id VARCHAR(255) PRIMARY KEY,
    assistant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(2000),
    allow_cross_origin BOOLEAN NOT NULL,
    tool_keys_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_api_integration_assistant_name UNIQUE (assistant_id, name)
);

CREATE TABLE IF NOT EXISTS waitlist_entries (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    company VARCHAR(255),
    use_case TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
