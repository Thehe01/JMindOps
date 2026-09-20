package com.kama.jmindops.agent;

import com.kama.jmindops.agent.tools.IdempotentTool;
import com.kama.jmindops.agent.tools.ToolIdempotencyResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ToolIdempotencyResolverTest {

    private final ToolIdempotencyResolver resolver = new ToolIdempotencyResolver();

    static class SampleToolService {
        @IdempotentTool(value = true)
        public String safeTool() {
            return "safe";
        }

        @IdempotentTool(value = false)
        public String unsafeTool() {
            return "unsafe";
        }

        @IdempotentTool(value = true, supportsIdempotencyKey = true)
        public String safeWithKey() {
            return "safeKey";
        }

        public String unannotatedTool() {
            return "none";
        }

        public String queryAndMutateDatabase() {
            return "dangerous";
        }
    }

    @Test
    void unannotatedToolsDefaultToDenyNonIdempotent() {
        // Unknown tools without annotation or whitelist must be default-deny
        assertThat(resolver.isIdempotent("unknownTool", null)).isFalse();
        assertThat(resolver.isIdempotent("transferFunds", null)).isFalse();
        assertThat(resolver.isIdempotent("executePayment", null)).isFalse();
    }

    @Test
    void prefixesDoNotHeuristicallyGrantIdempotency() throws Exception {
        // Crucial security test: prefixes like query, get, list must NOT blindly grant idempotency
        assertThat(resolver.isIdempotent("queryAndMutateDatabase", null)).isFalse();
        assertThat(resolver.isIdempotent("getAndChargeCard", null)).isFalse();
        assertThat(resolver.isIdempotent("listAndDeleteUsers", null)).isFalse();
        assertThat(resolver.isIdempotent("searchAndReplaceDisk", null)).isFalse();

        Method method = SampleToolService.class.getMethod("queryAndMutateDatabase");
        SampleToolService target = new SampleToolService();
        assertThat(resolver.isIdempotent("queryAndMutateDatabase", target, method)).isFalse();
    }

    @Test
    void builtinSafeToolsAreIdempotent() {
        assertThat(resolver.isIdempotent("knowledgeTool", null)).isTrue();
        assertThat(resolver.isIdempotent("databaseQuery", null)).isTrue();
        assertThat(resolver.isIdempotent("readFile", null)).isTrue();
        assertThat(resolver.isIdempotent("listDirectory", null)).isTrue();
        assertThat(resolver.isIdempotent("cityTool", null)).isTrue();
        assertThat(resolver.isIdempotent("terminate", null)).isTrue();
    }

    @Test
    void annotatedToolsRespectExplicitAnnotation() throws Exception {
        SampleToolService target = new SampleToolService();

        Method safeMethod = SampleToolService.class.getMethod("safeTool");
        assertThat(resolver.isIdempotent("safeTool", target, safeMethod)).isTrue();

        Method unsafeMethod = SampleToolService.class.getMethod("unsafeTool");
        assertThat(resolver.isIdempotent("unsafeTool", target, unsafeMethod)).isFalse();

        Method unannotated = SampleToolService.class.getMethod("unannotatedTool");
        assertThat(resolver.isIdempotent("unannotatedTool", target, unannotated)).isFalse();
    }
}
