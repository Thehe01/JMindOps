-- V12: Agent Durable Step Checkpoint, Execution Ledger, and Lease Fencing

-- 1. Extend generation_task with WAITING_APPROVAL status, worker lease, and fencing columns
ALTER TABLE generation_task
    DROP CONSTRAINT IF EXISTS generation_task_status_check;

ALTER TABLE generation_task
    ADD CONSTRAINT generation_task_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED'));

ALTER TABLE generation_task
    ADD COLUMN IF NOT EXISTS worker_id TEXT,
    ADD COLUMN IF NOT EXISTS lease_version BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'generation_task_lease_version_check'
          AND conrelid = 'generation_task'::regclass
    ) THEN
        ALTER TABLE generation_task
            ADD CONSTRAINT generation_task_lease_version_check CHECK (lease_version >= 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_generation_task_lease
    ON generation_task (status, lease_version, heartbeat_at);

-- 2. Durable agent checkpoint table (stores safe execution snapshots without hidden Chain-of-Thought)
CREATE TABLE IF NOT EXISTS agent_checkpoint (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES generation_task(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL,
    checkpoint_version BIGINT NOT NULL,
    stage TEXT NOT NULL,
    status TEXT NOT NULL,
    messages_payload TEXT NOT NULL,
    runtime_state TEXT NOT NULL,
    pending_tool_calls TEXT,
    tool_results TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_agent_checkpoint_generation_version UNIQUE (generation_id, checkpoint_version),
    CONSTRAINT agent_checkpoint_step_no_check CHECK (step_no >= 0),
    CONSTRAINT agent_checkpoint_version_check CHECK (checkpoint_version >= 0),
    CONSTRAINT agent_checkpoint_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoint_gen_step
    ON agent_checkpoint (generation_id, step_no DESC, checkpoint_version DESC);

-- 3. Execution ledger for tool invocations (guarantees idempotency and side-effect replay safety)
CREATE TABLE IF NOT EXISTS agent_tool_execution (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES generation_task(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL,
    tool_call_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    arguments TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PREPARED',
    result TEXT,
    error_message TEXT,
    is_idempotent BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_agent_tool_execution UNIQUE (generation_id, tool_call_id),
    CONSTRAINT agent_tool_execution_status_check
        CHECK (status IN ('PREPARED', 'EXECUTING', 'SUCCEEDED', 'WAITING_APPROVAL', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_execution_lookup
    ON agent_tool_execution (generation_id, step_no);

-- 4. Associate tool approvals with generation_task
ALTER TABLE tool_approval
    ADD COLUMN IF NOT EXISTS generation_id UUID REFERENCES generation_task(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS tool_call_id TEXT;

CREATE INDEX IF NOT EXISTS idx_tool_approval_generation
    ON tool_approval (generation_id, status);

CREATE INDEX IF NOT EXISTS idx_tool_approval_gen_call
    ON tool_approval (generation_id, tool_call_id);

-- 5. Grant permissions to application role if exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE agent_checkpoint, agent_tool_execution TO jmindops_app';
    END IF;
END
$$;
