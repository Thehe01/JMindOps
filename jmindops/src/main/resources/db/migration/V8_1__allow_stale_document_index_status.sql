ALTER TABLE document
    DROP CONSTRAINT IF EXISTS document_index_status_check;

ALTER TABLE document
    ADD CONSTRAINT document_index_status_check
    CHECK (
        index_status IN (
            'EMPTY',
            'READY',
            'STALE'
        )
    );
