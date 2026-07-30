-- =============================================================================
-- Lucien visit-interview mode
-- Binds a Lucien chat session to a single case (allocation_id), so the agent
-- loop can switch to the interview system prompt and allocation-scoped
-- context instead of the general FO-wide assistant. lucien_session_id on
-- visit_logs traces a submitted visit back to the chat transcript that
-- produced it, for audit.
-- =============================================================================
ALTER TABLE lucien_chat_sessions ADD COLUMN IF NOT EXISTS allocation_id UUID NULL;

CREATE INDEX IF NOT EXISTS idx_lucien_chat_sessions_allocation_id
    ON lucien_chat_sessions (allocation_id);

-- Loose FK by convention (same pattern as lucien_agent_steps.session_id) -
-- ChatSession.id is a VARCHAR(36) UUID string, not a native UUID/FK type.
ALTER TABLE visit_logs ADD COLUMN IF NOT EXISTS lucien_session_id VARCHAR(36) NULL;

CREATE INDEX IF NOT EXISTS idx_visit_logs_lucien_session_id
    ON visit_logs (lucien_session_id);
