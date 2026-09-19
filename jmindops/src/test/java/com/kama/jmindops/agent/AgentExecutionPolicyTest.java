package com.kama.jmindops.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPolicyTest {

    @Test
    void enforcesDirectoryListingBeforeDependentFileRead() {
        String instruction = AgentExecutionPolicy.instruction(
                RoutingDecision.MCP,
                "先列出 docs 目录，再读取其中的 README.md。"
        );

        assertThat(instruction)
                .contains("首次只调用 listFiles")
                .contains("才调用一次 readFile")
                .contains("禁止把两个调用并行发出")
                .contains("不要重复已经成功的调用");
    }

    @Test
    void limitsReadOnlyDatabaseQueryAndStopsAtApproval() {
        String instruction = AgentExecutionPolicy.instruction(
                RoutingDecision.MCP,
                "查询数据库中最近创建的五个知识库，只读即可。"
        );

        assertThat(instruction)
                .contains("最多调用一次 databaseQuery")
                .contains("等待审批")
                .contains("不得重试相同查询");
    }

    @Test
    void buildsHardSequenceForKnowledgeBaseAndWorkspaceCheck() {
        String request = "从知识库找到部署步骤，然后只读检查工作区配置文件是否一致。";

        assertThat(AgentExecutionPolicy.plan(RoutingDecision.RAG, request).requiredToolSequence())
                .containsExactly("KnowledgeTool", "readFile");
        assertThat(AgentExecutionPolicy.instruction(RoutingDecision.RAG, request))
                .contains("强制执行一次")
                .contains("只调用一次 readFile")
                .contains("禁止追加 listFiles");
    }

    @Test
    void forcesKnowledgeRetrievalForEveryRagRequest() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "请根据我的知识库解释依赖注入。"
        ).requiredToolSequence()).containsExactly("KnowledgeTool");
    }

    @Test
    void doesNotTreatFileCapabilityExplanationsAsWorkspaceReadAuthorization() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "先查知识库再核查工作区的组合任务允许哪些文件能力，哪些写能力仍被禁止？"
        ).requiredToolSequence()).containsExactly("KnowledgeTool");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "文件系统工具如何阻止越过指定工作区读取宿主机文件？"
        ).requiredToolSequence()).containsExactly("KnowledgeTool");
    }

    @Test
    void keepsExplicitWorkspaceReadInASeparateClauseAfterCapabilityExplanation() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "先说明允许哪些文件能力，然后读取工作区 settings.yml。"
        ).requiredToolSequence()).containsExactly("KnowledgeTool", "readFile");
    }

    @Test
    void plansSingleStepToolsDeterministically() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.WEATHER, "今天北京天气怎么样？").requiredToolSequence())
                .containsExactly("weather");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "读取工作区 notes/todo.md。").requiredToolSequence())
                .containsExactly("readFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "列出工作区 docs 目录。").requiredToolSequence())
                .containsExactly("listFiles");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "把 hello 写入工作区 output/demo.txt。").requiredToolSequence())
                .containsExactly("writeFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "向工作区 output/demo.txt 追加一行 done。").requiredToolSequence())
                .containsExactly("appendToFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "删除工作区 output/obsolete.txt。").requiredToolSequence())
                .containsExactly("deleteFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "在工作区创建 output/archive 目录。").requiredToolSequence())
                .containsExactly("createDirectory");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP, "给 test@example.com 发邮件。").requiredToolSequence())
                .containsExactly("sendEmail");
    }

    @Test
    void treatsViewingFolderContentsAsDirectoryListing() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "查看工作区 notes 文件夹下有哪些文件。").requiredToolSequence())
                .containsExactly("listFiles");
    }

    @Test
    void ordersViewingAListBeforeOpeningAFile() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "首先查看 notes 文件夹列表，然后打开 todo.md。").requiredToolSequence())
                .containsExactly("listFiles", "readFile");
    }

    @Test
    void recognizesNumberedAndParaphrasedDirectoryReadPlans() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "第一步列出 archive 目录，第二步读取 index.md。").requiredToolSequence())
                .containsExactly("listFiles", "readFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "先检查 configs 文件夹结构，随后打开 app.yaml。").requiredToolSequence())
                .containsExactly("listFiles", "readFile");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "打开 manuals 目录清单，然后读取 guide.md。").requiredToolSequence())
                .containsExactly("listFiles", "readFile");
    }

    @Test
    void excludesNegatedActionsFromPlans() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "根据知识库说明文件工具的边界，不要读取本地文件。").requiredToolSequence())
                .containsExactly("KnowledgeTool");
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "列出 archive 文件夹之后读取 index.md，不要写入任何文件。").requiredToolSequence())
                .containsExactly("listFiles", "readFile");
    }

    @Test
    void recognizesWorkspaceVerificationAfterKnowledgeRetrieval() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.RAG,
                "从知识库提取配置约束，再核对工作区 settings.yml 是否匹配。").requiredToolSequence())
                .containsExactly("KnowledgeTool", "readFile");
    }

    @Test
    void mapsCreatingAConcreteFileToWriteFile() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "创建工作区文件 output/result.txt，内容为 done。").requiredToolSequence())
                .containsExactly("writeFile");
    }

    @Test
    void doesNotTurnNegatedReadIntoSecondToolCall() {
        assertThat(AgentExecutionPolicy.plan(
                RoutingDecision.MCP,
                "打开工作区 docs 目录列表，不读取具体文件。").requiredToolSequence())
                .containsExactly("listFiles");
    }

    @Test
    void tellsApprovalGatedSingleStepToStopAfterWaitingResponse() {
        assertThat(AgentExecutionPolicy.instruction(
                RoutingDecision.MCP,
                "把 hello 写入工作区 output/demo.txt。"))
                .contains("只调用一次 writeFile")
                .contains("等待审批")
                .contains("不得重试");
    }

    @Test
    void buildsGeneralOrderedPlanAndStopsAtApprovalBoundary() {
        String request = "先读取工作区 notes/todo.md，然后把摘要写入 output/summary.txt，最后发送邮件通知。";

        assertThat(AgentExecutionPolicy.plan(RoutingDecision.MCP, request).requiredToolSequence())
                .containsExactly("readFile", "writeFile", "sendEmail");
        assertThat(AgentExecutionPolicy.instruction(RoutingDecision.MCP, request))
                .contains("readFile -> writeFile -> sendEmail")
                .contains("返回需要人工审批时，立即终止");
    }

    @Test
    void avoidsPlansForChatButPlansExplicitMcpRead() {
        assertThat(AgentExecutionPolicy.instruction(
                RoutingDecision.CHAT,
                "先解释概念，然后给一个例子。"
        )).isEmpty();
        assertThat(AgentExecutionPolicy.instruction(
                RoutingDecision.MCP,
                "读取 docs/README.md。"
        )).contains("只调用一次 readFile");
    }
}
