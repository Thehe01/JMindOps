package com.kama.jmindops.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagAnswerPolicyTest {

    @Test
    void groundingInstructionRejectsUnsupportedExpansionInsteadOfMemorizingCases() {
        assertThat(RagAnswerPolicy.groundingInstruction())
                .contains("只陈述证据明确表达的事实")
                .contains("不得把合理猜测、惯例或常识写成已证实结论")
                .contains("不得从“证据只列出或采用 A”反向推断未列出的 B 必然不存在")
                .contains("不得为证据没有定义的 ID、状态、字段或组件补充身份、职责和因果含义")
                .contains("若某个事实性句子找不到直接支持它的来源，就删除该句")
                .doesNotContain("rag-test-v2-011", "rag-test-v2-035");
    }

    @Test
    void countsOnlyActualKnowledgeToolSourceHeaders() {
        String context = """
                [Source 1 | documentId=doc-a]
                first

                [Source 2 | documentId=doc-b]
                second
                """;

        assertThat(RagAnswerPolicy.countSources(context)).isEqualTo(2);
        assertThat(RagAnswerPolicy.countSources("no evidence")).isZero();
    }

    @Test
    void removesFabricatedCitationsWithoutInventingAReplacement() {
        assertThat(RagAnswerPolicy.enforceValidCitations(
                "结论一 [Source 2]，错误引用 [Source 9]。", 2))
                .isEqualTo("结论一 [Source 2]，错误引用 。");
        assertThat(RagAnswerPolicy.enforceValidCitations("这是检索后的回答。", 2))
                .isEqualTo("这是检索后的回答。");
    }

    @Test
    void refusesWithoutEvidenceInsteadOfAllowingModelGuessing() {
        assertThat(RagAnswerPolicy.enforceValidCitations("模型猜测的答案", 0))
                .isEqualTo(RagAnswerPolicy.INSUFFICIENT_EVIDENCE_MESSAGE);
    }

    @Test
    void canonicalizesOverallGroundedRefusalAndDropsIrrelevantCitations() {
        assertThat(RagAnswerPolicy.enforceValidCitations(
                "根据当前知识库中的信息，没有找到所问事实。[Source 1]", 2))
                .isEqualTo(RagAnswerPolicy.INSUFFICIENT_EVIDENCE_MESSAGE);
        assertThat(RagAnswerPolicy.enforceValidCitations(
                "在当前的知识库中，未提供该配置，因此无法确定。[Source 2]", 2))
                .isEqualTo(RagAnswerPolicy.INSUFFICIENT_EVIDENCE_MESSAGE);
        assertThat(RagAnswerPolicy.enforceValidCitations(
                "知识库中没有找到相关信息。[Source 1]", 1))
                .isEqualTo(RagAnswerPolicy.INSUFFICIENT_EVIDENCE_MESSAGE);
    }

    @Test
    void keepsSupportedAnswerWhenOnlyALaterSubquestionLacksEvidence() {
        assertThat(RagAnswerPolicy.enforceValidCitations(
                "系统使用 Redis 缓存。[Source 1] 但当前资料未说明默认 TTL。", 1))
                .isEqualTo("系统使用 Redis 缓存。[Source 1] 但当前资料未说明默认 TTL。");
    }
}
