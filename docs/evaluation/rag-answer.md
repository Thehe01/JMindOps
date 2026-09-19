# JMindOps RAG 端到端回答评测

检索 Hit/MRR 只能说明“相关片段是否被找到”。本评测从用户问题开始，真实执行 RouterAgent、KnowledgeTool、RAG 检索和最终生成，补充答案完整性、引用与无答案拒答指标。

## 题集契约

开发回归复用 `evaluation-data/rag-gold-v1.json`；已经曝光并参与失败题优化的 `evaluation-data/rag-test-v1.json` 只作历史回归。下一轮正式盲测使用已冻结的 `evaluation-data/rag-test-v2.json`；项目所有者已确认完成 `evaluation-data/rag-test-v2-review.md` 中的逐题人工复核。每题必须有稳定 `id`：

~~~json
{
  "id": "rag-answer-001",
  "query": "requestId 和 generationId 分别解决什么问题？",
  "expectedKeywords": ["requestId", "generationId"]
}
~~~

对表述容易变化的答案，不要把某一种措辞当成唯一字符串真值。使用概念别名组；每组命中任意一个别名即视为覆盖一个概念：

~~~json
{
  "id": "rag-answer-alias-001",
  "query": "审计日志会保留哪些调用信息？",
  "expectedConcepts": [
    {"anyOf": ["全部外部工具", "所有外部工具"]},
    {"anyOf": ["原始的 Prompt", "原始 Prompt"]}
  ]
}
~~~

`expectedConcepts` 与旧字段 `expectedKeywords` 二选一；前者存在时评分器优先按概念组计算。别名仍必须逐项出现在人工锁定的第一主证据中，不能用同义词组掩盖语料不支持的问题。

评分器 v2 会先做 Unicode NFKC、大小写、Markdown 标记、空白、箭头和常见否定表达归一化，再在同一句或同一行内校验紧邻的中文语义锚点与代码标识。这样可接受 `` `FAILED` ``、`FAILED`、插入“任务/字段”等短修饰词的等价写法，同时不会把分散在不同段落的词拼成一个概念，也不会把“可以”误判成“不能”。报告会保存命中的具体概念别名、评分器版本和评分器 SHA-256，便于复核与重算。该规则仍是确定性语义代理，不等同于自然语言蕴含模型；角色到地址、组件到职责等映射必须在同一语义单元内完整出现，并继续接受人工抽检。

无答案题使用：

~~~json
{
  "id": "rag-no-answer-001",
  "query": "知识库中不存在的信息是什么？",
  "expectedNoAnswer": true,
  "answerMustNotContain": ["禁止出现的臆造内容"]
}
~~~

`answerEvaluationDefaults` 可统一设置 `expectedRoute`、`requireKnowledgeTool`、`requireCitation`、`requireNoCitationForNoAnswer`、`minimumKeywordRecall` 和 `refusalMustContainAny`。逐题可用同名 `minimumKeywordRecall` 覆盖默认值；枚举完整性题应设为 `1.0`，防止漏答一个要点仍通过。若某题明确只要求命中三个要点中的两个，才使用 `0.66`（因为 `2/3 < 0.67`），并在题集复核记录中说明原因。常见的可靠拒答措辞还应覆盖“未提供、未说明、未提及、无法确定”。正式结果必须先人工核查语料确实支持可回答题、确实不包含无答案题信息，记录语料和开发集文件 Hash，并将题集设置为 `annotationStatus=REVIEWED`、`datasetRole=test`、`frozen=true`。

## 运行

~~~powershell
$env:JMINDOPS_TOKEN = "登录后取得的 JWT"
./scripts/run-rag-answer-evaluation.ps1 `
  -AgentId "REPLACE_WITH_AGENT_UUID" `
  -DatasetPath ./evaluation-data/rag-gold-v1.json
~~~

试跑尚未审核的题集必须显式增加 `-AllowDraft`。也可用 `-CaseIds` 只复测 Bad Case。轮询间隔会根据任务是否有进展在 2～15 秒之间动态调整；连续题目默认至少间隔 6 秒提交，避免无答案快路径集中触发每用户 12 次/分钟的固定窗口限流。若仍收到 429，Runner 会遵循 `Retry-After` 退避并用同一幂等请求安全重试。

正式报告运行一次预热和至少三次完整评测：

~~~powershell
./scripts/run-formal-rag-evaluation.ps1 `
  -AgentId "<rag-agent-uuid>" `
  -KnowledgeBaseId "<knowledge-base-uuid>"
~~~

输出位于 `evaluation-results/`：

- `*-observed.json`：最终回答、KnowledgeTool 上下文、来源编号、模型、Token、延迟及运行溯源；
- `*-report.json`：机器可读评分；
- `*.md`：指标表和失败检查。

JSON 与 Markdown 报告同时记录 `scorer.name`、`scorer.version`、`scorer.sha256` 和概念匹配策略。评分规则升级后的重算产物必须使用新文件名，不能覆盖原始正式报告。

### 固定语义 Judge（二层评分）

确定性评分完成后，可在同一份 Observed 上运行固定语义 Judge。它接管成功可回答题的概念完整性，并对可回答题和无答案题统一检查证据忠实度与回答相关性；终态、路由、KnowledgeTool、答案存在、引用存在/编号有效及拒答形式仍由确定性硬门禁决定。无答案题即使包含“未说明”等拒答词，只要同时夹带具体值、地址、时间、数量或其他无依据断言，也会判为不忠实。这样不会用 Judge 掩盖执行故障，也不会因确定性词面规则漏掉合理同义表达而误判。

~~~powershell
python ./scripts/judge-rag-answer-eval.py `
  --dataset ./evaluation-data/rag-gold-v1.json `
  --observed ./evaluation-results/<run>-observed.json `
  --deterministic-report ./evaluation-results/<run>-report.json `
  --api-config D:/project/smartship/.api_key `
  --output ./evaluation-results/<run>-hybrid-judge.json `
  --markdown ./evaluation-results/<run>-hybrid-judge.md
~~~

配置文件仍使用 `RELAY_API_KEY`、`RELAY_BASE_URL`、`RELAY_CHAT_MODEL`；如需让生成模型与裁判模型分离，可额外配置 `RELAY_JUDGE_MODEL`。脚本固定 `temperature=0`、`reasoning_effort=low`，通过 `max_completion_tokens` 默认最多输出 1,200 tokens，每题最多请求一次且不自动重试；逐题 checkpoint 支持中断后续跑。发送给 Judge 的内容仅包含问题、是否应拒答、Gold 概念、最终回答及回答实际引用的来源，且有长度上限。无答案题的 Gold 概念和引用来源均为空，Judge 只验证拒答完整性以及是否夹带编造事实。报告记录 Judge/Prompt/脚本、题集、Observed 和确定性报告的 SHA-256，不记录 API Key。

双层报告必须区分 `INFRASTRUCTURE_FAILURE`、`GENERATION_FAILURE`、`HARD_GATE_FAILED`、`JUDGE_ERROR`、`SEMANTIC_QUALITY_FAILED` 与 `NO_ANSWER_SEMANTIC_FAILED`。缺少旧版 Observed 的 `terminalError` 时只能保守归类为其他生成失败；新版 Runner 会保存后端已脱敏的 `lastError`，HTTP 5xx、网络或超时可自动归为基础设施失败。

### 忠实度失败的修复边界

对 `SEMANTIC_QUALITY_FAILED` 的修复必须收敛为通用回答策略，不能把测试题 ID、问题文本或标准答案写入 Prompt。当前运行时要求模型只陈述证据明确表达的事实：不能从“证据列出/采用 A”反向断言未列出的 B 不存在，也不能为证据未定义的 ID、状态、字段或组件补充身份、职责和因果含义；输出前应删除没有直接证据或与问题无关的扩展句。确定性引用清洗仍只负责删除越界来源编号，不能替代语义忠实度判断。

一旦依据冻结测试集的失败样本修改回答策略，该测试集就已暴露，只能继续作为回归集。修改后的无泄漏正式结果必须使用重新人工复核并冻结的新测试集；不得通过重跑原测试集宣称新的盲测成绩。

对已经暴露且其锁定开发集后来发生变化的旧测试集，回归 Runner 使用显式 `allow-exposed-test-regression` 校验模式：题集本身仍必须是 `REVIEWED / datasetRole=test / frozen=true`，题集文件、主语料证据和全部题目契约仍会校验，只跳过已经失去“未见测试”意义的旧开发集 Hash/重复度比较。该模式只允许带 `RegressionCaseIds` 的回归流程，manifest 会记录 `exposedTestRegression=true`，不得用于正式验收。

Runner 在开始和结束时调用 provenance 接口；知识库快照或检索配置在执行期间变化时不会生成正式报告。每次产物还记录已提交差异、未跟踪文件清单的 Hash 及组合工作区指纹，即使 Git 为 dirty 也能识别两次运行是否来自相同代码状态。

Observed 包含私有知识库原文，仅应保存在本机；该目录已被 Git 忽略。

## 指标与边界

| 指标 | 回答的问题 |
| --- | --- |
| Answer Keyword/Concept Recall | 标准答案关键词或概念别名组在最终回答中覆盖多少 |
| Answer Completeness Pass Rate | 关键词召回是否达到逐题/默认阈值 |
| Citation Presence Rate | 要求引用的回答是否给出 `[Source N]` |
| Citation Index Validity Rate | 引用编号是否确实存在于本次 KnowledgeTool 上下文；只代表格式合法 |
| Citation Evidence Coverage | 被引用来源覆盖多少 Gold 关键词 |
| Citation Evidence Pass Rate | 被引用来源的关键词覆盖是否达到逐题阈值 |
| No-answer Accuracy | 无依据时是否明确拒答且未触发禁用断言 |
| Case Pass Rate | 路由、工具、终态、答案和引用是否全部通过 |
| P50/P95、Average Tokens | 端到端成本与尾延迟 |

确定性评分器是可复算的回归门禁。运行时只删除越界引用，不会在模型漏引时自动追加 `[Source 1]`。确定性引用证据覆盖仍只证明被引上下文含有 Gold 词项；双层报告中的 `faithfulnessPassRate` 才是固定 LLM Judge 的上下文级忠实度判定，但它仍不等于人工事实核验。对外报告前应在固定样本上计算人工—Judge 一致率；在真正接入并调用 RAGAS 前，仍不能把本实现直接命名为 RAGAS。
