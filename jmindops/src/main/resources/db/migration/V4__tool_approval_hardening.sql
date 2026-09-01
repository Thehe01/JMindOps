ALTER TABLE tool_approval
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'tool_approval_session_required'
          AND conrelid = 'tool_approval'::regclass
    ) THEN
        -- NOT VALID preserves legacy rows while enforcing the invariant for new rows.
        ALTER TABLE tool_approval
            ADD CONSTRAINT tool_approval_session_required
            CHECK (session_id IS NOT NULL) NOT VALID;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_tool_approval_atomic_consume
    ON tool_approval
    (user_id, session_id, tool_name, fingerprint, status, expires_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO jmindops_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE '
            || 'agent, chat_session, chat_message, knowledge_base, document, chunk_bge_m3, '
            || 'app_user, tool_approval, tool_audit_log TO jmindops_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public '
            || 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jmindops_app';
    END IF;
END
$$;
