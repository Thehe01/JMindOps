ALTER TABLE generation_task
    ADD COLUMN IF NOT EXISTS routing_decision TEXT,
    ADD COLUMN IF NOT EXISTS current_step INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cumulative_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS checkpoint_version BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'generation_task_current_step_check'
          AND conrelid = 'generation_task'::regclass
    ) THEN
        ALTER TABLE generation_task
            ADD CONSTRAINT generation_task_current_step_check CHECK (current_step >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'generation_task_cumulative_tokens_check'
          AND conrelid = 'generation_task'::regclass
    ) THEN
        ALTER TABLE generation_task
            ADD CONSTRAINT generation_task_cumulative_tokens_check CHECK (cumulative_tokens >= 0);
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS agent_step_trace (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES generation_task(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'THINKING',
    model_name TEXT,
    has_tool_calls BOOLEAN NOT NULL DEFAULT FALSE,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    model_latency_ms BIGINT,
    output_length INTEGER NOT NULL DEFAULT 0,
    output_hash CHAR(64),
    last_error TEXT,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT agent_step_trace_generation_step_unique UNIQUE (generation_id, step_no),
    CONSTRAINT agent_step_trace_step_no_check CHECK (step_no > 0),
    CONSTRAINT agent_step_trace_status_check
        CHECK (status IN ('THINKING', 'EXECUTING_TOOLS', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT agent_step_trace_non_negative_check
        CHECK (tool_call_count >= 0 AND prompt_tokens >= 0 AND completion_tokens >= 0
            AND total_tokens >= 0 AND output_length >= 0)
);

CREATE TABLE IF NOT EXISTS tool_invocation_trace (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES generation_task(id) ON DELETE CASCADE,
    step_trace_id UUID NOT NULL REFERENCES agent_step_trace(id) ON DELETE CASCADE,
    tool_call_id TEXT NOT NULL,
    invocation_order INTEGER NOT NULL,
    tool_name TEXT NOT NULL,
    arguments_length INTEGER NOT NULL DEFAULT 0,
    arguments_hash CHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'PREPARED',
    result_length INTEGER,
    result_hash CHAR(64),
    latency_ms BIGINT,
    last_error TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT tool_invocation_trace_call_unique UNIQUE (generation_id, tool_call_id),
    CONSTRAINT tool_invocation_trace_order_unique UNIQUE (step_trace_id, invocation_order),
    CONSTRAINT tool_invocation_trace_status_check
        CHECK (status IN ('PREPARED', 'RUNNING', 'SUCCEEDED', 'WAITING_APPROVAL', 'FAILED', 'UNKNOWN')),
    CONSTRAINT tool_invocation_trace_non_negative_check
        CHECK (invocation_order > 0 AND arguments_length >= 0
            AND (result_length IS NULL OR result_length >= 0))
);

CREATE INDEX IF NOT EXISTS idx_agent_step_trace_generation
    ON agent_step_trace (generation_id, step_no);
CREATE INDEX IF NOT EXISTS idx_agent_step_trace_status
    ON agent_step_trace (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_tool_invocation_trace_generation
    ON tool_invocation_trace (generation_id, invocation_order);
CREATE INDEX IF NOT EXISTS idx_tool_invocation_trace_status
    ON tool_invocation_trace (status, updated_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE agent_step_trace, tool_invocation_trace TO jmindops_app';
    END IF;
END
$$;
