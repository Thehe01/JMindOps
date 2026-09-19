CREATE EXTENSION IF NOT EXISTS pg_search;

-- Jieba performs Chinese word segmentation for both indexed content and query
-- text. Including kb_id and id lets ParadeDB apply the knowledge-base filter
-- and deterministic tiebreaker inside its Top-K scan.
CREATE INDEX IF NOT EXISTS idx_chunk_bm25
    ON chunk_bge_m3
    USING paradedb (
        id,
        kb_id,
        (content::pdb.jieba)
    )
    WITH (key_field = 'id');
