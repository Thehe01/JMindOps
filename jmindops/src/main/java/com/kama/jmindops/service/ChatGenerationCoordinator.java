package com.kama.jmindops.service;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * 会话生成协调器。
 * 支持基于 Redis 的分布式会话协调与 Single-Flight 控制（带 TTL 防死锁），
 * 同时在无 Redis 环境下平滑降级为 JVM 内存级协调器。
 */
@Component

public class ChatGenerationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatGenerationCoordinator.class);

    private static final String RESERVED = "reserved:";
    private static final String RUNNING = "running:";
    private static final String KEY_PREFIX = "jmindops:generation:session:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    // Lua 脚本：原子 claim
    private static final String CLAIM_LUA_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
            else
                return 0
            end
            """;

    // Lua 脚本：原子 release
    private static final String RELEASE_LUA_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if current == ARGV[1] or current == ARGV[2] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    // Lua 脚本：看门狗续期
    private static final String RENEW_LUA_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('expire', KEYS[1], ARGV[2])
            else
                return 0
            end
            """;

    // 仅当锁不存在或仍属于同一个 PENDING 任务时恢复预占；RUNNING 与其他任务一律拒绝。
    private static final String RESTORE_LUA_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if not current then
                redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2])
                return 1
            elseif current == ARGV[1] then
                redis.call('expire', KEYS[1], ARGV[2])
                return 1
            else
                return 0
            end
            """;

    private final StringRedisTemplate redisTemplate;
    // 用于内存降级，以及看门狗追踪当前实例持有的 active generation
    private final ConcurrentMap<String, String> memoryGenerations = new ConcurrentHashMap<>();

    public ChatGenerationCoordinator() {
        this.redisTemplate = null;
    }

    @Autowired(required = false)
    public ChatGenerationCoordinator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 每隔 1 分钟执行一次，为当前实例正在运行的锁续期
     */
    @Scheduled(fixedDelay = 60000)
    public void renewLocks() {
        if (redisTemplate == null || memoryGenerations.isEmpty()) return;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RENEW_LUA_SCRIPT, Long.class);
        String ttlStr = String.valueOf(DEFAULT_TTL.toSeconds());
        int successCount = 0;

        for (Map.Entry<String, String> entry : memoryGenerations.entrySet()) {
            if (entry.getValue().startsWith(RUNNING)) {
                try {
                    Long result = redisTemplate.execute(
                            script,
                            Collections.singletonList(buildKey(entry.getKey())),
                            entry.getValue(),
                            ttlStr
                    );
                    if (result != null && result == 1L) {
                        successCount++;
                    }
                } catch (Exception e) {
                    log.warn("Watchdog failed to renew lock for session: {}", entry.getKey());
                }
            }
        }
        if (successCount > 0) {
            log.debug("Watchdog successfully renewed {} locks", successCount);
        }
    }

    public String reserve(String sessionId) {
        String generationId = UUID.randomUUID().toString();
        String reservedValue = RESERVED + generationId;

        if (redisTemplate != null) {
            try {
                String key = buildKey(sessionId);
                Boolean success = redisTemplate.opsForValue().setIfAbsent(key, reservedValue, DEFAULT_TTL);
                if (Boolean.FALSE.equals(success)) {
                    throw new BizException(409, "该会话已有生成任务，请等待完成后再发送");
                }
                return generationId;
            } catch (BizException be) {
                throw be;
            } catch (Exception e) {
                log.error("[ChatGenerationCoordinator] Redis reserve error, fail-closed to prevent split-brain: {}", e.getMessage());
                throw new BizException(500, "分布式协调服务异常，为保证一致性已拒绝任务");
            }
        }

        // Memory mode (only when Redis is explicitly disabled/absent)
        if (memoryGenerations.putIfAbsent(sessionId, reservedValue) != null) {
            throw new BizException(409, "该会话已有生成任务，请等待完成后再发送");
        }
        return generationId;
    }

    public boolean claim(String sessionId, String generationId) {
        String expectedReserved = RESERVED + generationId;
        String newRunning = RUNNING + generationId;

        if (redisTemplate != null) {
            try {
                String key = buildKey(sessionId);
                DefaultRedisScript<Long> script = new DefaultRedisScript<>(CLAIM_LUA_SCRIPT, Long.class);
                Long result = redisTemplate.execute(
                        script,
                        Collections.singletonList(key),
                        expectedReserved,
                        newRunning,
                        String.valueOf(DEFAULT_TTL.toSeconds())
                );
                if (result != null && result == 1L) {
                    memoryGenerations.put(sessionId, newRunning);
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.error("[ChatGenerationCoordinator] Redis claim error, fail-closed: {}", e.getMessage());
                throw new BizException(500, "分布式协调服务异常，为保证一致性已拒绝任务");
            }
        }

        return memoryGenerations.replace(sessionId, expectedReserved, newRunning);
    }

    /**
     * 为数据库中待恢复的 PENDING 任务重建会话预占。该操作不会抢占正在运行的任务。
     */
    public boolean restoreReservation(String sessionId, String generationId) {
        String expectedReserved = RESERVED + generationId;
        if (redisTemplate != null) {
            try {
                DefaultRedisScript<Long> script = new DefaultRedisScript<>(RESTORE_LUA_SCRIPT, Long.class);
                Long result = redisTemplate.execute(
                        script,
                        Collections.singletonList(buildKey(sessionId)),
                        expectedReserved,
                        String.valueOf(DEFAULT_TTL.toSeconds())
                );
                return result != null && result == 1L;
            } catch (Exception e) {
                log.error("[ChatGenerationCoordinator] Redis restore error, fail-closed: {}", e.getMessage());
                throw new BizException(500, "分布式协调服务异常，为保证一致性已拒绝任务恢复");
            }
        }

        String current = memoryGenerations.putIfAbsent(sessionId, expectedReserved);
        return current == null || expectedReserved.equals(current);
    }

    /**
     * Returns only generations currently owned as RUNNING by this application instance.
     * The persistent task heartbeat uses this snapshot to distinguish a slow model call
     * from a process that has stopped renewing both Redis and database leases.
     */
    public Map<String, String> runningGenerationsSnapshot() {
        Map<String, String> running = new java.util.HashMap<>();
        memoryGenerations.forEach((sessionId, value) -> {
            if (value != null && value.startsWith(RUNNING)) {
                running.put(sessionId, value.substring(RUNNING.length()));
            }
        });
        return Map.copyOf(running);
    }

    public void release(String sessionId, String generationId) {
        String expectedReserved = RESERVED + generationId;
        String expectedRunning = RUNNING + generationId;

        if (redisTemplate != null) {
            try {
                String key = buildKey(sessionId);
                DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LUA_SCRIPT, Long.class);
                redisTemplate.execute(
                        script,
                        Collections.singletonList(key),
                        expectedReserved,
                        expectedRunning
                );
            } catch (Exception e) {
                log.error("[ChatGenerationCoordinator] Redis release error: {}", e.getMessage());
            }
            memoryGenerations.remove(sessionId, expectedRunning);
            return;
        }

        String current = memoryGenerations.get(sessionId);
        if (expectedReserved.equals(current) || expectedRunning.equals(current)) {
            memoryGenerations.remove(sessionId);
        }
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
