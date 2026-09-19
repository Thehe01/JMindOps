ALTER TABLE document
    ADD COLUMN IF NOT EXISTS index_fingerprint CHAR(64);

ALTER TABLE chunk_bge_m3
    ADD COLUMN IF NOT EXISTS index_fingerprint CHAR(64);

CREATE INDEX IF NOT EXISTS idx_chunk_kb_index_fingerprint
    ON chunk_bge_m3 (kb_id, index_fingerprint);

-- Legacy vectors intentionally keep a NULL fingerprint. Their next upload must
-- rebuild them with the active embedding/parser pipeline instead of reusing an
-- embedding whose model identity cannot be proven.
UPDATE document
SET index_status = 'STALE'
WHERE chunk_count > 0
  AND index_fingerprint IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_tool_reader') THEN
        EXECUTE format(
            'REVOKE TEMPORARY ON DATABASE %I FROM jmindops_tool_reader',
            current_database()
        );
        EXECUTE 'GRANT USAGE ON SCHEMA public TO jmindops_tool_reader';
        EXECUTE 'GRANT SELECT ON TABLE '
            || 'agent, chat_session, chat_message, knowledge_base, document, chunk_bge_m3 '
            || 'TO jmindops_tool_reader';
        EXECUTE 'REVOKE ALL ON TABLE app_user, tool_approval, tool_audit_log, generation_task '
            || 'FROM jmindops_tool_reader';
    END IF;
END
$$;
