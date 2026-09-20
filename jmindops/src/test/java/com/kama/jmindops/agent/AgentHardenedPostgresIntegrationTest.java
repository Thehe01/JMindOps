package com.kama.jmindops.agent;

import com.kama.jmindops.agent.checkpoint.CheckpointPayload;
import com.kama.jmindops.agent.tools.ToolIdempotencyResolver;
import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.model.entity.AgentCheckpoint;
import com.kama.jmindops.model.entity.AgentToolExecution;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.service.AgentCheckpointStore;
import com.kama.jmindops.service.AgentResumeService;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.GenerationTaskRecovery;
import com.kama.jmindops.service.GenerationTaskStore;
import com.kama.jmindops.service.SseService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres-integration")
class AgentHardenedPostgresIntegrationTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static GenerationTaskStore taskStore;
    private static AgentCheckpointStore checkpointStore;
    private static boolean postgresAvailable = false;

    private static final String TEST_USER_ID = "00000000-0000-0000-0000-000000000099";
    private static final String TEST_SESSION_ID = "00000000-0000-0000-0000-000000000088";
    private static final String TEST_AGENT_ID = "00000000-0000-0000-0000-000000000077";

    @BeforeAll
    static void setUpAll() {
        String urlProp = System.getProperty("spring.datasource.url");
        if (urlProp == null || urlProp.isBlank()) {
            urlProp = System.getenv("SPRING_DATASOURCE_URL");
        }

        String jdbcUrl = null;
        int targetPort = -1;
        if (urlProp != null && !urlProp.isBlank()) {
            jdbcUrl = urlProp;
        } else {
            for (int port : new int[]{5432, 5433}) {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    targetPort = port;
                    break;
                } catch (Exception ignored) {}
            }
            if (targetPort > 0) {
                String db = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
                jdbcUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + db;
            }
        }

        if (jdbcUrl == null) {
            return;
        }

        String username = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");

        try {
            dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
            jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("SELECT 1");

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();
            flyway.migrate();

            taskStore = new GenerationTaskStore(jdbcTemplate);
            checkpointStore = new AgentCheckpointStore(jdbcTemplate);

            jdbcTemplate.execute("INSERT INTO app_user (id, username, password_hash, role) " +
                    "VALUES ('" + TEST_USER_ID + "', 'test_user_postgres', 'hash', 'USER') " +
                    "ON CONFLICT (id) DO NOTHING");

            jdbcTemplate.execute("INSERT INTO agent (id, name, model) " +
                    "VALUES ('" + TEST_AGENT_ID + "', 'test_agent', 'gpt-test') " +
                    "ON CONFLICT (id) DO NOTHING");

            jdbcTemplate.execute("INSERT INTO chat_session (id, agent_id, owner_id) " +
                    "VALUES ('" + TEST_SESSION_ID + "', '" + TEST_AGENT_ID + "', '" + TEST_USER_ID + "') " +
                    "ON CONFLICT (id) DO NOTHING");

            postgresAvailable = true;
        } catch (Exception e) {
            postgresAvailable = false;
        }
    }

    @BeforeEach
    void requirePostgres() {
        Assumptions.assumeTrue(postgresAvailable, "Live PostgreSQL required for postgres-integration tests");
    }

    /**
     * 1. stale lease cannot mutate
     */
    @Test
    void staleLeaseCannotMutate() {
        String genId = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();

        taskStore.createPending(genId, null, TEST_USER_ID, reqId, "fp-stale",
                TEST_AGENT_ID, TEST_SESSION_ID, null, "stale lease test");

        long lease1 = taskStore.claimForExecution(genId, "worker-1");
        assertThat(lease1).isEqualTo(1L);

        // Advance lease to 2 by another worker (e.g. timeout recovery or takeover)
        long lease2 = taskStore.claimForResume(genId, "worker-2", 1L);
        assertThat(lease2).isEqualTo(2L);

        // Stale worker-1 tries to mark succeeded with old lease 1 -> rejected!
        assertThatThrownBy(() -> taskStore.markSucceeded(genId, "worker-1", 1L))
                .isInstanceOf(StaleGenerationLeaseException.class)
                .hasMessageContaining("rejected by fencing");

        // Stale worker-1 tries to mark waiting approval with old lease 1 -> rejected!
        assertThatThrownBy(() -> taskStore.markWaitingApproval(genId, "worker-1", 1L))
                .isInstanceOf(StaleGenerationLeaseException.class)
                .hasMessageContaining("rejected by fencing");

        // Stale worker-1 tries to touch heartbeat with old lease 1 -> returns false!
        boolean touched = taskStore.touchHeartbeat(genId, "worker-1", 1L);
        assertThat(touched).isFalse();

        // Stale worker-1 tries to mark failed with old lease 1 -> returns false, does not update!
        boolean failed = taskStore.markFailed(genId, "worker-1", 1L, "should not succeed");
        assertThat(failed).isFalse();

        // Verify state in PostgreSQL: still owned by worker-2, lease 2, still RUNNING
        GenerationTask current = taskStore.findExecutionTask(genId).orElseThrow();
        assertThat(current.status()).isEqualTo(GenerationTask.Status.RUNNING);
        assertThat(current.workerId()).isEqualTo("worker-2");
        assertThat(current.leaseVersion()).isEqualTo(2L);
    }

    /**
     * 2. two workers cannot own same recovery
     */
    @Test
    void twoWorkersCannotOwnSameRecovery() throws Exception {
        String genId = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();

        taskStore.createPending(genId, null, TEST_USER_ID, reqId, "fp-rec",
                TEST_AGENT_ID, TEST_SESSION_ID, null, "two workers recovery test");

        taskStore.claimForExecution(genId, "crashed-worker");

        // Force heartbeat into the distant past so task is eligible as stale RUNNING
        jdbcTemplate.update("""
                UPDATE generation_task
                SET heartbeat_at = NOW() - INTERVAL '7200 second',
                    started_at = NOW() - INTERVAL '7200 second',
                    updated_at = NOW() - INTERVAL '7200 second'
                WHERE id = CAST(? AS uuid)
                """, genId);

        int workerCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<List<GenerationTask>> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 1; i <= workerCount; i++) {
            final String workerId = "recovery-worker-" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    List<GenerationTask> claimed = taskStore.claimStaleRunningForResume(
                            workerId, Duration.ofSeconds(60), 10);
                    List<GenerationTask> matching = claimed.stream()
                            .filter(t -> t.id().equals(genId))
                            .toList();
                    results.add(matching);
                } catch (Exception e) {
                    results.add(List.of());
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        int totalClaimed = results.stream().mapToInt(List::size).sum();
        assertThat(totalClaimed).as("Exactly one worker must claim the stale generation").isEqualTo(1);

        // Verify PostgreSQL row
        GenerationTask current = taskStore.findExecutionTask(genId).orElseThrow();
        assertThat(current.leaseVersion()).isEqualTo(2L);
        assertThat(current.workerId()).startsWith("recovery-worker-");
    }

    /**
     * 3. checkpoint CAS/fencing
     */
    @Test
    void checkpointCasFencing() {
        String genId = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();

        taskStore.createPending(genId, null, TEST_USER_ID, reqId, "fp-ckpt",
                TEST_AGENT_ID, TEST_SESSION_ID, null, "checkpoint fencing test");

        long lease1 = taskStore.claimForExecution(genId, "worker-1");
        assertThat(lease1).isEqualTo(1L);

        // Worker 1 commits checkpoint 1
        AgentCheckpoint cp1 = new AgentCheckpoint(
                UUID.randomUUID().toString(), genId, 1, 1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT, GenerationTask.Status.RUNNING,
                "[]", "{}", null, null, null, null
        );
        checkpointStore.saveCheckpoint(cp1, "worker-1", 1L);

        Long cpVersionInGt = jdbcTemplate.queryForObject(
                "SELECT checkpoint_version FROM generation_task WHERE id = CAST(? AS uuid)", Long.class, genId);
        assertThat(cpVersionInGt).isEqualTo(1L);

        // Worker 2 takes over the task (lease bumps to 2)
        long lease2 = taskStore.claimForResume(genId, "worker-2", 1L);
        assertThat(lease2).isEqualTo(2L);

        // Stale Worker 1 attempts to save checkpoint 2 with lease 1 -> rejected by fencing!
        AgentCheckpoint cp2Stale = new AgentCheckpoint(
                UUID.randomUUID().toString(), genId, 2, 2L,
                AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.RUNNING,
                "[]", "{}", null, null, null, null
        );
        assertThatThrownBy(() -> checkpointStore.saveCheckpoint(cp2Stale, "worker-1", 1L))
                .isInstanceOf(StaleGenerationLeaseException.class)
                .hasMessageContaining("rejected by fencing");

        // Verify cp2Stale was rolled back and NOT inserted
        Optional<AgentCheckpoint> latest = checkpointStore.findLatestCheckpoint(genId);
        assertThat(latest).isPresent();
        assertThat(latest.get().checkpointVersion()).isEqualTo(1L);

        // Valid Worker 2 commits checkpoint 2 with lease 2 -> succeeds!
        AgentCheckpoint cp2Valid = new AgentCheckpoint(
                UUID.randomUUID().toString(), genId, 2, 2L,
                AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.RUNNING,
                "[]", "{}", null, null, null, null
        );
        checkpointStore.saveCheckpoint(cp2Valid, "worker-2", 2L);

        latest = checkpointStore.findLatestCheckpoint(genId);
        assertThat(latest).isPresent();
        assertThat(latest.get().checkpointVersion()).isEqualTo(2L);
    }

    /**
     * 4. tool execution unique(generation_id, tool_call_id)
     */
    @Test
    void toolExecutionUniqueGenerationIdAndToolCallId() {
        String genId = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();

        taskStore.createPending(genId, null, TEST_USER_ID, reqId, "fp-ledger",
                TEST_AGENT_ID, TEST_SESSION_ID, null, "tool execution ledger test");

        String toolCallId = "tc-ledger-" + UUID.randomUUID();
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                toolCallId, "function", "paymentTool", "{\"amount\":50}"
        );

        // 1. Record prepared tool call
        checkpointStore.recordPreparedToolCall(genId, 1, toolCall, false);

        Optional<AgentToolExecution> exec1 = checkpointStore.findToolExecution(genId, toolCallId);
        assertThat(exec1).isPresent();
        assertThat(exec1.get().status()).isEqualTo(AgentToolExecution.Status.PREPARED);

        // 2. Duplicate recordPreparedToolCall with same (generation_id, tool_call_id) -> DO NOTHING
        checkpointStore.recordPreparedToolCall(genId, 1, toolCall, false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_tool_execution WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?",
                Integer.class, genId, toolCallId);
        assertThat(count).isEqualTo(1);

        // 3. Raw INSERT violating UNIQUE constraint -> throws DuplicateKeyException
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO agent_tool_execution (
                    id, generation_id, step_no, tool_call_id, tool_name, arguments, status, is_idempotent
                ) VALUES (
                    gen_random_uuid(), CAST(? AS uuid), 1, ?, 'paymentTool', '{}', 'PREPARED', false
                )
                """, genId, toolCallId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * 5. stale RUNNING recovery actually resumes
     */
    @Test
    void staleRunningRecoveryActuallyResumes() throws Exception {
        String genId = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();

        taskStore.createPending(genId, null, TEST_USER_ID, reqId, "fp-resume",
                TEST_AGENT_ID, TEST_SESSION_ID, null, "stale recovery resume test");

        taskStore.claimForExecution(genId, "crashed-worker-initial");

        // Record a checkpoint with a pending tool call in MODEL_OUTPUT stage
        String toolCallId = "tc-res-" + UUID.randomUUID();
        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall(toolCallId, "function", "cityTool", "{\"cityName\":\"Hangzhou\"}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> messages = List.of(new UserMessage("What is the city?"));
        String messagesPayload = CheckpointPayload.serializeMessages(messages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 50L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(), genId, 1, 1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT, GenerationTask.Status.RUNNING,
                messagesPayload, runtimeStateJson, pendingCallsJson, null, null, null
        );
        checkpointStore.saveCheckpoint(checkpoint, "crashed-worker-initial", 1L);
        checkpointStore.recordPreparedToolCall(genId, 1, pendingCalls.get(0), true);

        // Age the heartbeat to simulate stale running timeout
        jdbcTemplate.update("""
                UPDATE generation_task
                SET heartbeat_at = NOW() - INTERVAL '7200 second',
                    started_at = NOW() - INTERVAL '7200 second',
                    updated_at = NOW() - INTERVAL '7200 second'
                WHERE id = CAST(? AS uuid)
                """, genId);

        // Setup mock coordinator, factory, sseService
        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator();
        SseService sseService = mock(SseService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        ToolCallback cityTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("cityTool");
        when(cityTool.getToolDefinition()).thenReturn(toolDef);
        when(cityTool.call(any(), any())).thenReturn("Hangzhou is the capital of Zhejiang");

        ChatClient mockChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(mockChatClient.prompt(any(Prompt.class))).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(reqSpec);
        when(reqSpec.stream()).thenReturn(streamSpec);
        AssistantMessage finalAnswer = new AssistantMessage("Hangzhou is a beautiful city.");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(finalAnswer)));
        when(streamSpec.chatResponse()).thenReturn(reactor.core.publisher.Flux.just(chatResponse));

        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        when(factory.createForResume(any(GenerationTask.class), anyString(), anyLong()))
                .thenAnswer(inv -> {
                    GenerationTask t = inv.getArgument(0);
                    String workerId = inv.getArgument(1);
                    long leaseVersion = inv.getArgument(2);
                    return new JMindOps(
                            TEST_AGENT_ID, "test-agent", "", "", mockChatClient,
                            20, 0.0, 0.9, messages, List.of(cityTool), List.of(),
                            TEST_SESSION_ID, t.id(), publisher, null,
                            RoutingDecision.CHAT, "What is the city?",
                            AgentExecutionPolicy.plan(RoutingDecision.CHAT, "What is the city?"),
                            Duration.ofSeconds(60), checkpointStore, new ToolIdempotencyResolver(),
                            workerId, leaseVersion
                    );
                });

        AgentResumeService resumeService = new AgentResumeService(
                taskStore, coordinator, factory, sseService);

        GenerationTaskRecovery recovery = new GenerationTaskRecovery(
                taskStore, coordinator, publisher, sseService, resumeService,
                15, 600, 20);

        // Trigger recovery
        recovery.recover();

        // Wait for async resume to complete
        boolean succeeded = false;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            GenerationTask check = taskStore.findExecutionTask(genId).orElseThrow();
            if (check.status() == GenerationTask.Status.SUCCEEDED) {
                succeeded = true;
                break;
            }
        }

        assertThat(succeeded).as("Task must automatically resume and transition to SUCCEEDED").isTrue();

        GenerationTask finalTask = taskStore.findExecutionTask(genId).orElseThrow();
        assertThat(finalTask.status()).isEqualTo(GenerationTask.Status.SUCCEEDED);
        assertThat(finalTask.leaseVersion()).isGreaterThanOrEqualTo(2L);
        assertThat(finalTask.completedAt()).isNotNull();

        // Verify tool execution succeeded in ledger
        Optional<AgentToolExecution> toolExec = checkpointStore.findToolExecution(genId, toolCallId);
        assertThat(toolExec).isPresent();
        assertThat(toolExec.get().status()).isEqualTo(AgentToolExecution.Status.SUCCEEDED);
        assertThat(toolExec.get().result()).isEqualTo("Hangzhou is the capital of Zhejiang");
    }
}
