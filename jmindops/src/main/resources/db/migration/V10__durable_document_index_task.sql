CREATE TABLE IF NOT EXISTS document_index_task (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    index_version INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    worker_id TEXT,
    last_error TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    file_path TEXT NOT NULL,
    filename TEXT NOT NULL,
    filetype TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    source_key TEXT NOT NULL,
    index_fingerprint CHAR(64) NOT NULL,
    is_new_document BOOLEAN NOT NULL DEFAULT false,
    old_file_path TEXT,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    reused_chunk_count INTEGER NOT NULL DEFAULT 0,
    embedded_chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT document_index_task_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT document_index_task_retry_count_check CHECK (retry_count >= 0),
    CONSTRAINT document_index_task_max_retries_check CHECK (max_retries >= 0),
    CONSTRAINT uk_document_index_task_doc_version UNIQUE (document_id, index_version)
);

CREATE INDEX IF NOT EXISTS idx_doc_index_task_claim
    ON document_index_task (status, next_retry_at, created_at);
CREATE INDEX IF NOT EXISTS idx_doc_index_task_recovery
    ON document_index_task (status, heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_doc_index_task_document
    ON document_index_task (document_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_doc_index_task_kb_source
    ON document_index_task (kb_id, source_key);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE document_index_task TO jmindops_app';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_tool_reader') THEN
        EXECUTE 'REVOKE ALL ON TABLE document_index_task FROM jmindops_tool_reader';
    END IF;
END
$$;
