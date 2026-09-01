package com.kama.jmindops.agent.tools;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.governance.ToolExecutionContext;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.RagService;
import com.kama.jmindops.service.RagSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final ResourceAccessService resourceAccessService;

    public KnowledgeTools(RagService ragService, ResourceAccessService resourceAccessService) {
        this.ragService = ragService;
        this.resourceAccessService = resourceAccessService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        if (!StringUtils.hasText(kbsId) || !StringUtils.hasText(query)) {
            throw new BizException("知识库 ID 和查询内容不能为空");
        }
        if (!ToolExecutionContext.isKnowledgeBaseAllowed(kbsId)) {
            throw new BizException("当前 Agent 无权访问该知识库");
        }
        // Agent allowlist 不能替代租户归属校验：两者都通过才允许进入 RAG 服务。
        resourceAccessService.requireOwnedKnowledgeBase(kbsId);

        List<RagSource> sources = ragService.hybridSearchWithSources(kbsId, query);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            RagSource source = sources.get(index);
            result.append("[Source ").append(index + 1)
                    .append(" | documentId=").append(source.documentId()).append("]\n")
                    .append(source.content()).append("\n\n");
        }
        return result.toString();
    }
}
