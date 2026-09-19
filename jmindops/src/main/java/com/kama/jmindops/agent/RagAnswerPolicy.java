package com.kama.jmindops.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 最终答案的确定性后置条件：只允许引用本次检索实际返回的来源编号。
 */
final class RagAnswerPolicy {
    static final String INSUFFICIENT_EVIDENCE_MESSAGE =
            "当前知识库中没有检索到足够可靠的依据，因此无法根据现有资料回答这个问题。";
    static final String GROUNDING_INSTRUCTION = """
            - 本轮是严格 RAG 回答：只能依据当前对话中的 KnowledgeTool 结果，不得使用模型记忆补充未被证据支持的事实。
            - 逐项覆盖用户问题中的每个子问题或枚举项；每个事实性结论就近标注真正支持该结论的 [Source N]，不得编造或使用装饰性引用。
            - 只陈述证据明确表达的事实，允许不改变含义的概括和对多条明示事实的直接合并；不得把合理猜测、惯例或常识写成已证实结论。
            - 不得从“证据只列出或采用 A”反向推断未列出的 B 必然不存在，也不得为证据没有定义的 ID、状态、字段或组件补充身份、职责和因果含义。
            - 回答完成后逐句自检：若某个事实性句子找不到直接支持它的来源，就删除该句或明确说明当前资料未说明；与问题无关的扩展说明应删除。
            - 只有部分子问题缺少证据时，保留有证据的答案并明确指出缺失项。
            - 整个问题都没有足够证据时，只输出“%s”，不得附加任何 [Source N]。
            """.formatted(INSUFFICIENT_EVIDENCE_MESSAGE);

    private static final Pattern SOURCE_HEADER = Pattern.compile(
            "\\[Source\\s+(\\d+)\\s*\\|\\s*documentId=[^\\]\\s]+\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CITATION = Pattern.compile(
            "\\[Source\\s+(\\d+)(?:\\s*\\|[^\\]]+)?\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERALL_GROUNDED_REFUSAL = Pattern.compile(
            "^\\s*(?:(?:根据|基于)\\s*)?(?:在\\s*)?(?:(?:当前|现有)(?:的)?)?"
                    + "(?:知识库|资料|文档)(?:中的信息|中|里)?[，,:：\\s]*"
                    + "(?:没有找到|没有检索到|没有查找到|未找到|未检索到|未查找到|未提供|未说明|未提及)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private RagAnswerPolicy() {
    }

    static String groundingInstruction() {
        return GROUNDING_INSTRUCTION;
    }

    static int countSources(String toolResponse) {
        if (toolResponse == null || toolResponse.isBlank()) {
            return 0;
        }
        int maxIndex = 0;
        Matcher matcher = SOURCE_HEADER.matcher(toolResponse);
        while (matcher.find()) {
            maxIndex = Math.max(maxIndex, Integer.parseInt(matcher.group(1)));
        }
        return maxIndex;
    }

    static String enforceValidCitations(String answer, int availableSourceCount) {
        if (availableSourceCount <= 0) {
            return INSUFFICIENT_EVIDENCE_MESSAGE;
        }
        String candidate = answer == null ? "" : answer.trim();
        if (OVERALL_GROUNDED_REFUSAL.matcher(candidate).find()) {
            return INSUFFICIENT_EVIDENCE_MESSAGE;
        }
        Matcher matcher = CITATION.matcher(candidate);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= availableSourceCount) {
                matcher.appendReplacement(sanitized, Matcher.quoteReplacement("[Source " + index + "]"));
            } else {
                matcher.appendReplacement(sanitized, "");
            }
        }
        matcher.appendTail(sanitized);

        String result = sanitized.toString().replaceAll("[ \\t]+(?=\\R|$)", "").trim();
        if (result.isBlank()) {
            return INSUFFICIENT_EVIDENCE_MESSAGE;
        }
        return result;
    }
}
