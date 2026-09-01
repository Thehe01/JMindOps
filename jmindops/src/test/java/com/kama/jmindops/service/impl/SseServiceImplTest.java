package com.kama.jmindops.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseServiceImplTest {

    @Test
    void limitsConcurrentConnectionsPerSession() {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());
        for (int index = 0; index < 5; index++) {
            assertThat(service.connect("session-1")).isNotNull();
        }

        assertThatThrownBy(() -> service.connect("session-1"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(429));
    }
}
