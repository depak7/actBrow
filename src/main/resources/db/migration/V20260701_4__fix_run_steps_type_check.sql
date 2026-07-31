-- The run_steps.type column is @Enumerated(STRING); Hibernate generated a run_steps_type_check
-- CHECK constraint when the table was first created, frozen at the enum values of that time.
-- Since ddl-auto=none, later-added step types (VERIFIER_DECISION, POLICY_DECISION) violate it.
-- Recreate the constraint with the full current RunStepType set.
ALTER TABLE run_steps DROP CONSTRAINT IF EXISTS run_steps_type_check;
ALTER TABLE run_steps ADD CONSTRAINT run_steps_type_check CHECK (
    type IN ('MODEL_DECISION', 'TOOL_CALL', 'TOOL_RESULT', 'VERIFIER_DECISION', 'POLICY_DECISION', 'FINAL_RESPONSE')
);
