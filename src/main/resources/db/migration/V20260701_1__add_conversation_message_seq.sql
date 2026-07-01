-- Adds a monotonic tiebreaker for conversation_messages ordering.
-- Two messages appended in the same millisecond (an ASSISTANT tool_calls envelope and its TOOL
-- result) previously sorted non-deterministically, which could break tool_calls/tool pairing for
-- the model. Messages are now ordered by (created_at, seq). Nullable: existing rows keep NULL and
-- are unaffected (their created_at values are already distinct); new rows get a per-process seq.
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS seq BIGINT;
