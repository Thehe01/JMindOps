package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceStoreTest {
    @Test
    void hashesTracePayloadsDeterministicallyWithoutKeepingTheOriginalValue() {
        String secretArguments = "{\"apiKey\":\"should-not-be-persisted\"}";

        String hash = AgentTraceStore.sha256(secretArguments);

        assertThat(hash)
                .hasSize(64)
                .isEqualTo(AgentTraceStore.sha256(secretArguments))
                .doesNotContain("should-not-be-persisted");
        assertThat(AgentTraceStore.sha256(secretArguments + "x")).isNotEqualTo(hash);
    }

    @Test
    void recognizesApprovalResponsesWithoutPersistingTheirRawContents() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(sql ->
                sql.contains("SET status = 'UNKNOWN'") ? 0 : 1);
        AgentTraceStore store = new AgentTraceStore(jdbcTemplate);
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "writeFile", "\"操作需要人工审批，审批编号：approval-1。\"")))
                .build();

        store.completeTools("generation-1", "step-1", message, 42);

        SqlCall responseUpdate = jdbcTemplate.calls.stream()
                .filter(call -> call.sql().contains("tool_call_id = ?"))
                .findFirst()
                .orElseThrow();
        assertThat(responseUpdate.arguments()[0]).isEqualTo("WAITING_APPROVAL");
        assertThat(responseUpdate.arguments()).noneMatch(argument ->
                String.valueOf(argument).contains("approval-1"));
    }

    @Test
    void marksStepUnknownWhenAToolResponseCannotBeCorrelated() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(sql ->
                sql.contains("SET status = 'UNKNOWN'") ? 1 : 0);
        AgentTraceStore store = new AgentTraceStore(jdbcTemplate);

        store.completeTools(
                "generation-1",
                "step-1",
                ToolResponseMessage.builder().responses(List.of()).build(),
                42
        );

        SqlCall stepUpdate = jdbcTemplate.calls.stream()
                .filter(call -> call.sql().contains("UPDATE agent_step_trace"))
                .findFirst()
                .orElseThrow();
        assertThat(stepUpdate.arguments()[0]).isEqualTo("UNKNOWN");
        assertThat(stepUpdate.arguments()[1]).isEqualTo("存在无法关联结果的工具调用");
    }

    @Test
    void traceOperationsDoNotMutateHeartbeatOrCheckpointVersion() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(sql -> 1);
        AgentTraceStore store = new AgentTraceStore(jdbcTemplate);
        String genId = "00000000-0000-0000-0000-000000000001";
        String workerId = "worker-fenced";
        long leaseVersion = 3L;

        // 1. recordRouting
        store.recordRouting(genId, "RAG_SEARCH", workerId, leaseVersion);

        // 2. startStep
        String stepId = store.startStep(genId, 1, workerId, leaseVersion);

        // 3. completeThinking
        store.completeThinking(genId, stepId, null, null, 100L, "gpt-4", workerId, leaseVersion);

        // 4. completeTools
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool-1", "result")))
                .build();
        store.completeTools(genId, stepId, message, 50L, workerId, leaseVersion);

        // Verify that NO SQL statement mutates heartbeat_at or checkpoint_version on generation_task
        for (SqlCall call : jdbcTemplate.calls) {
            if (call.sql().contains("generation_task")) {
                assertThat(call.sql())
                        .as("SQL updating generation_task must not touch checkpoint_version")
                        .doesNotContain("checkpoint_version");
                assertThat(call.sql())
                        .as("SQL updating generation_task must not touch heartbeat_at")
                        .doesNotContain("heartbeat_at");
                assertThat(call.sql())
                        .as("SQL updating generation_task must be fenced by worker_id and lease_version")
                        .contains("worker_id = ?")
                        .contains("lease_version = ?");
            }
        }
    }

    private record SqlCall(String sql, Object[] arguments) {
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<SqlCall> calls = new ArrayList<>();
        private final ToIntFunction<String> result;

        private RecordingJdbcTemplate(ToIntFunction<String> result) {
            this.result = result;
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(new SqlCall(sql, args));
            return result.applyAsInt(sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            calls.add(new SqlCall(sql, args));
            if (requiredType == String.class) {
                return requiredType.cast(java.util.UUID.randomUUID().toString());
            }
            return null;
        }
    }
}
