package com.kama.jmindops.ai;

import com.kama.jmindops.exception.StaleGenerationLeaseException;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import static org.assertj.core.api.Assertions.*;

class LangChain4jToolExecutionTest {
    @Test
    void parsesNamedArgumentsAndInvokesTheSpringProxy() {
        Fixture fixture = new Fixture();
        java.util.concurrent.atomic.AtomicInteger intercepted = new java.util.concurrent.atomic.AtomicInteger();
        ProxyFactory factory = new ProxyFactory(fixture);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            intercepted.incrementAndGet();
            return invocation.proceed();
        });
        ToolCallback callback = MethodToolCallbackProvider.builder().toolObjects(factory.getProxy()).build().getToolCallbacks()[0];
        assertThat(callback.getToolDefinition().name()).isEqualTo("echo");
        assertThat(callback.getToolDefinition().specification().parameters().properties()).containsKey("value");
        assertThat(callback.call("{\"value\":\"hello\"}")).isEqualTo("hello");
        assertThat(intercepted).hasValue(1);
    }

    @Test
    void propagatesOriginalFencingExceptionInsteadOfTreatingItAsASuccessfulString() {
        ToolCallback callback = MethodToolCallbackProvider.builder().toolObjects(new FailingFixture()).build().getToolCallbacks()[0];
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(StaleGenerationLeaseException.class).hasMessage("lease expired");
    }

    public static class Fixture {
        @Tool(name = "echo", value = "Echo a value")
        public String echo(String value) { return value; }
    }
    public static class FailingFixture {
        @Tool(name = "fail", value = "Fail the call")
        public String fail() { throw new StaleGenerationLeaseException("lease expired"); }
    }
}
