ALTER TABLE document_index_task
    ADD COLUMN lease_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE document_index_task
    ADD CONSTRAINT document_index_task_lease_version_check CHECK (lease_version >= 0);

ALTER TABLE document_index_task
    DROP CONSTRAINT document_index_task_status_check,
    ADD CONSTRAINT document_index_task_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_doc_index_task_ordering
    ON document_index_task (document_id, index_version);

CREATE INDEX IF NOT EXISTS idx_doc_index_task_active_claim
    ON document_index_task (status, cancel_requested, next_retry_at, created_at);
