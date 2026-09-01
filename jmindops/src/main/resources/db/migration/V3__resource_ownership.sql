ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE knowledge_base
    ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE chat_session
    ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_agent_owner
    ON agent (owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_owner
    ON knowledge_base (owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_session_owner
    ON chat_session (owner_id, updated_at DESC);

COMMENT ON COLUMN agent.owner_id IS
    'Legacy rows may be NULL and must be explicitly assigned before shared deployment.';
