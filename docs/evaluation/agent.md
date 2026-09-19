# JMindOps Agent 业务评测

这套评测验证应用内 Agent 的真实路由、工具治理和执行轨迹，不使用 BFCL 分数代替业务效果。

## 评测范围

`evaluation-data/agent-eval-v1.json` 当前为人工复核的 `REVIEWED` 基线，覆盖：

- CHAT、RAG、WEATHER、MCP 与多步任务路由；
- 必须工具、禁止工具、精确调用顺序和最大调用次数；
- 最大 Agent 步数、生成任务终态；
- 高风险工具进入 `WAITING_APPROVAL` 的治理行为；
- 可选的参数 Hash 和最终回答断言。

参数只保留 SHA-256，Trace 不落原始工具参数和结果。当前基线尚未配置参数语义真值，也只验证“发起审批”，不代表批准、拒绝、篡改及重放的完整生命周期已覆盖。

## v2 开发集与冻结测试集

为避免用正式测试题反复调规则，v2 拆成两个互不重复的文件：

- `evaluation-data/agent-eval-v2-dev.json`：40 题开发集，可以查看逐题结果并修复；
- `evaluation-data/agent-eval-v2-test.json`：100 题冻结测试集，只用于最终验收。

两份题集已完成逐题复核并标记为 `REVIEWED`，其中测试集已设置 `frozen: true`。开发集覆盖 CHAT/RAG/WEATHER 路由、工具选择、治理、安全和多步骤执行；测试集包含 24 道路由边界题、20 道工具选择题、20 道多步骤题、16 道审批治理题和 20 道安全题。

运行任何真实评测前先执行静态校验：

~~~powershell
python -X utf8 ./scripts/validate-agent-eval-datasets.py `
  ./evaluation-data/agent-eval-v2-dev.json `
  ./evaluation-data/agent-eval-v2-test.json
~~~

人工复核必须逐题确认：

1. 输入是否只有一个清晰的用户意图，措辞是否自然；
2. `route` 是否符合产品边界，而不是迎合当前实现；
3. 固定只读夹具是否只引用 `docs/README.md`、`notes/todo.md` 及其目录；
4. `requiredTools`、`forbiddenTools` 与 `expectedToolSequence` 是否一致；
5. 写入、追加、删除、建目录、邮件和数据库查询是否进入 `WAITING_APPROVAL`；
6. RAG 是否恰好调用一次 `KnowledgeTool`，多步骤是否严格按依赖顺序执行；
7. `maxToolCalls`、`maxSteps` 和最终回答断言是否合理。

当前开发集与测试集均为 `REVIEWED`，测试集已冻结；此后测试集任何内容变化都必须提升 `datasetId`。如果查看冻结集逐题结果后据此修改实现，该版本即发生测试泄漏，必须另建新的冻结测试版本。

当前单轮 Runner 无法真实注入多轮记忆、进程崩溃、外部服务超时和并发竞争，因此这些能力明确不计入 100 题覆盖率，继续由集成测试验证。

## 单次真实运行

~~~powershell
$env:JMINDOPS_TOKEN = "登录后取得的 JWT"
./scripts/run-agent-evaluation.ps1 `
  -AgentId "REPLACE_WITH_AGENT_UUID" `
  -DatasetPath ./evaluation-data/agent-eval-v2-dev.json
~~~

每题创建隔离会话，通过生成任务接口动态轮询：状态或 `updatedAt` 变化时恢复短间隔，没有进展时逐步放大到 15 秒。结果写入被 Git 忽略的 `evaluation-results/`：

- `*-observed.json`：单次真实 Trace 证据；
- `*-report.json`：确定性评分结果。

报告区分 `SINGLE_RUN` 与 `COMPOSITE`。面试或简历优先引用同一 Agent、同一 API、同一题集 Hash 的完整 `SINGLE_RUN`；不要把不同配置的零散补跑拼成一次完整实验。

只补跑失败题：

~~~powershell
./scripts/run-agent-evaluation.ps1 `
  -AgentId "REPLACE_WITH_AGENT_UUID" `
  -CaseIds rag-002,multi-step-001
~~~

## 合并补跑证据

~~~powershell
./scripts/merge-agent-evaluation.ps1 `
  -DatasetPath ./evaluation-data/agent-eval-v1.json `
  -ObservedPaths ./evaluation-results/run-a-observed.json,./evaluation-results/run-b-observed.json
~~~

合并器默认拒绝未知或重复 case、题集 Hash 不一致、不同 AgentId、不同 Agent 配置快照或不同 API 地址。Agent 快照包含模型、System Prompt Hash、工具/知识库白名单与 ChatOptions；同一 AgentId 被修改后也不能悄悄混分。后出现的 case 会替换前一次并在 `replacements` 中记录，因此合并产物属于 `COMPOSITE`，必须如实标注。

## 指标解释

`casePassRate` 是最严格的整题指标：路由、必选/禁用工具、审批、调用序列、调用次数、参数、最终回答、步数和终态中任一已配置检查失败，整题即失败。`metricSupport` 必须与指标一起报告；支持题数为 0 的指标不能宣称达到 0% 或 100%。
