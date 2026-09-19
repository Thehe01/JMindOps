package com.kama.jmindops.service;

import com.kama.jmindops.JMindOpsApplication;
import com.kama.jmindops.exception.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatGenerationCoordinatorTest {

    @Test
    void enablesScheduledWatchdogInTheApplication() {
        assertThat(JMindOpsApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }

    @Test
    void reservesClaimsAndReleasesOneGenerationPerSessionInMemory() {
        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator();
        String generationId = coordinator.reserve("session-1");

        assertThatThrownBy(() -> coordinator.reserve("session-1"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        assertThat(coordinator.claim("session-1", generationId)).isTrue();
        assertThat(coordinator.claim("session-1", generationId)).isFalse();
        assertThat(coordinator.runningGenerationsSnapshot()).containsEntry("session-1", generationId);

        coordinator.release("session-1", generationId);
        assertThat(coordinator.runningGenerationsSnapshot()).isEmpty();
        String nextGenerationId = coordinator.reserve("session-1");
        assertThat(nextGenerationId).isNotEqualTo(generationId);
    }

    @Test
    void rejectsUnknownGenerationClaimsInMemory() {
        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator();
        String generationId = coordinator.reserve("session-1");

        assertThat(coordinator.claim("session-1", "other-generation")).isFalse();
        assertThat(coordinator.claim("session-1", generationId)).isTrue();
    }

    @Test
    void restoresOnlyTheSamePendingGenerationInMemory() {
        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator();

        assertThat(coordinator.restoreReservation("session-recovery", "generation-a")).isTrue();
        assertThat(coordinator.restoreReservation("session-recovery", "generation-a")).isTrue();
        assertThat(coordinator.restoreReservation("session-recovery", "generation-b")).isFalse();
        assertThat(coordinator.claim("session-recovery", "generation-a")).isTrue();
        assertThat(coordinator.restoreReservation("session-recovery", "generation-a")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservesClaimsAndReleasesUsingRedisWhenAvailable() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        when(valueOperations.setIfAbsent(eq("jmindops:generation:session:session-redis"), anyString(), any(Duration.class)))
                .thenReturn(true);

        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator(redisTemplate);
        String generationId = coordinator.reserve("session-redis");
        assertThat(generationId).isNotBlank();

        // Mock execute with 3 vararg arguments for claim
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(1L);

        boolean claimed = coordinator.claim("session-redis", generationId);
        assertThat(claimed).isTrue();

        coordinator.release("session-redis", generationId);
        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), eq("reserved:" + generationId), eq("running:" + generationId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renewsClaimedRedisLocksWithTheExpectedGeneration() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any()))
                .thenReturn(1L);

        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator(redisTemplate);
        assertThat(coordinator.claim("session-watchdog", "generation-a")).isTrue();

        coordinator.renewLocks();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                eq("running:generation-a"),
                eq("300")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void lateReleaseDoesNotRemoveANewerGenerationFromWatchdogTracking() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any()))
                .thenReturn(1L);

        ChatGenerationCoordinator coordinator = new ChatGenerationCoordinator(redisTemplate);
        assertThat(coordinator.claim("session-race", "generation-old")).isTrue();
        assertThat(coordinator.claim("session-race", "generation-new")).isTrue();

        coordinator.release("session-race", "generation-old");
        coordinator.renewLocks();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                eq("running:generation-new"),
                eq("300")
        );
    }
}
