-- Indexes for the agent hot path and the dashboard read paths.
--
-- The core tables (conversations, conversation_messages, runs, run_steps, tools,
-- assistant_tool_bindings, knowledge_documents, navigation_flows, api_integrations, assistants)
-- predate Flyway, and Hibernate only ever emitted primary-key and unique-column indexes for them.
-- Every non-unique foreign-key column below was therefore unindexed, which means a fresh
-- self-hosted install sequential-scans the hottest table in the system on every planning step.
--
-- IF NOT EXISTS keeps this a no-op where an environment already has them. Plain CREATE INDEX
-- (not CONCURRENTLY) because Flyway runs each migration inside a transaction and CONCURRENTLY
-- cannot run there; these tables are small enough that the brief lock is acceptable.

-- Read once per planning step, ordered, and pulls every content TEXT for the conversation.
-- This is the single hottest query in the run loop.
CREATE INDEX IF NOT EXISTS idx_conv_messages_conv_created_seq
    ON conversation_messages (conversation_id, created_at, seq);

-- Highest-write table; scanned by run inspection and insights, dragging TOASTed payloads.
CREATE INDEX IF NOT EXISTS idx_run_steps_run_step
    ON run_steps (run_id, step_index);

-- Tool catalog assembly: once per run, plus once per progressive-disclosure meta-tool call.
CREATE INDEX IF NOT EXISTS idx_bindings_assistant_tool
    ON assistant_tool_bindings (assistant_id, tool_id);
CREATE INDEX IF NOT EXISTS idx_bindings_tool
    ON assistant_tool_bindings (tool_id);

-- Run lookups by conversation (inspection) and by assistant (insights, dashboard lists).
CREATE INDEX IF NOT EXISTS idx_runs_conversation
    ON runs (conversation_id);
CREATE INDEX IF NOT EXISTS idx_runs_assistant_created
    ON runs (assistant_id, created_at DESC);

-- Conversation and assistant listings, hit on most authenticated dashboard requests.
CREATE INDEX IF NOT EXISTS idx_conversations_assistant
    ON conversations (assistant_id);
CREATE INDEX IF NOT EXISTS idx_assistants_user
    ON assistants (user_id);

-- knowledge.search loads every enabled document for the assistant.
CREATE INDEX IF NOT EXISTS idx_knowledge_assistant_enabled
    ON knowledge_documents (assistant_id, enabled);

-- Navigation flows are read once per planning step when usePredefinedFlows is on.
CREATE INDEX IF NOT EXISTS idx_nav_flows_assistant
    ON navigation_flows (assistant_id);

CREATE INDEX IF NOT EXISTS idx_api_integrations_assistant
    ON api_integrations (assistant_id);
