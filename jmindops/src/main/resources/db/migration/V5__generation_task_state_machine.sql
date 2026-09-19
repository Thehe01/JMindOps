CREATE TABLE IF NOT EXISTS generation_task (
    id UUID PRIMARY KEY,
    parent_generation_id UUID REFERENCES generation_task(id) ON DELETE SET NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    user_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    input_content TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_dispatched_at TIMESTAMP NOT NULL DEFAULT NOW(),
    heartbeat_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT generation_task_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT generation_task_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT generation_task_user_request_unique UNIQUE (user_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_generation_task_recovery
    ON generation_task (status, last_dispatched_at, heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_generation_task_session_created
    ON generation_task (session_id, created_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE generation_task TO jmindops_app';
    END IF;
END
$$;
