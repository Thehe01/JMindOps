ALTER TABLE document
    ADD COLUMN IF NOT EXISTS source_key TEXT,
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS index_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS index_status TEXT NOT NULL DEFAULT 'EMPTY',
    ADD COLUMN IF NOT EXISTS chunk_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS indexed_at TIMESTAMP;

UPDATE document
SET source_key = LOWER(REGEXP_REPLACE(BTRIM(filename), '\s+', ' ', 'g'))
WHERE source_key IS NULL;

-- 旧库可能已经存在同名文档。保留第一条的自然 source_key，其余记录追加 ID，
-- 从而能安全建立约束，又不在迁移阶段静默删除用户数据。
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY kb_id, source_key ORDER BY created_at, id) AS row_num
    FROM document
)
UPDATE document AS target
SET source_key = target.source_key || ':' || target.id::text
FROM ranked
WHERE target.id = ranked.id
  AND ranked.row_num > 1;

ALTER TABLE document
    ALTER COLUMN source_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_kb_source_key
    ON document (kb_id, source_key);

ALTER TABLE chunk_bge_m3
    ADD COLUMN IF NOT EXISTS chunk_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS chunk_index INTEGER,
    ADD COLUMN IF NOT EXISTS document_version INTEGER NOT NULL DEFAULT 1;

UPDATE chunk_bge_m3
SET chunk_hash = ENCODE(SHA256(CONVERT_TO(content, 'UTF8')), 'hex')
WHERE chunk_hash IS NULL;

WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY doc_id ORDER BY created_at, id) - 1 AS position
    FROM chunk_bge_m3
)
UPDATE chunk_bge_m3 AS target
SET chunk_index = ordered.position
FROM ordered
WHERE target.id = ordered.id
  AND target.chunk_index IS NULL;

ALTER TABLE chunk_bge_m3
    ALTER COLUMN chunk_hash SET NOT NULL,
    ALTER COLUMN chunk_index SET NOT NULL;

UPDATE document AS target
SET index_status = CASE WHEN stats.chunk_count > 0 THEN 'READY' ELSE 'EMPTY' END,
    index_version = CASE WHEN stats.chunk_count > 0 THEN 1 ELSE 0 END,
    chunk_count = stats.chunk_count,
    indexed_at = stats.indexed_at
FROM (
    SELECT document.id,
           COUNT(chunk_bge_m3.id)::INTEGER AS chunk_count,
           MAX(chunk_bge_m3.updated_at) AS indexed_at
    FROM document
    LEFT JOIN chunk_bge_m3 ON chunk_bge_m3.doc_id = document.id
    GROUP BY document.id
) AS stats
WHERE target.id = stats.id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'document_index_status_check'
          AND conrelid = 'document'::regclass
    ) THEN
        ALTER TABLE document
            ADD CONSTRAINT document_index_status_check
            CHECK (index_status IN ('EMPTY', 'READY'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'document_index_version_check'
          AND conrelid = 'document'::regclass
    ) THEN
        ALTER TABLE document
            ADD CONSTRAINT document_index_version_check CHECK (index_version >= 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'document_chunk_count_check'
          AND conrelid = 'document'::regclass
    ) THEN
        ALTER TABLE document
            ADD CONSTRAINT document_chunk_count_check CHECK (chunk_count >= 0);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_chunk_document_position
    ON chunk_bge_m3 (doc_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_chunk_document_hash
    ON chunk_bge_m3 (doc_id, chunk_hash);
