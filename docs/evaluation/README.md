# JMindOps Agent / RAG 真实评测

业务 Agent 评测见 [Agent 业务评测](./agent.md)，最终回答层见 [RAG 端到端回答评测](./rag-answer.md)。本页聚焦 RAG 检索层。三层结果分别报告，不能用检索命中率代替 Agent 整题成功率或最终答案正确性。

公共中文检索基准 T2Ranking 的准备、导入和分级指标复算流程见
[JMindOps 接入 T2Ranking](./t2ranking.md)。

检索评测用于回答四个问题：

1. 纯向量召回是否已经足够；
2. Jieba + BM25 倒排召回与 RRF 是否改善错误码、类名、版本号等精确词问题；
3. 部署 Reranker 后，它带来的质量收益是否值得额外的尾延迟。
4. 对知识库中不存在的问题，检索链路能否返回空结果而不是强行给出候选。

它只评估检索层，不等同于最终答案忠实度或完整 Agent 任务成功率。

## 评测模式

| 模式 | 行为 |
| --- | --- |
| VECTOR | 只执行 BGE-M3 + pgvector 向量召回 |
| HYBRID_RRF | 向量召回 + Jieba/BM25 倒排召回 + RRF |
| HYBRID_RERANK | 可选模式；仅在配置 Reranker Provider 后执行 |

仓库默认使用 `rag.reranker.provider=none`，不部署重排模型。此时请求 HYBRID_RERANK 会返回 `NOT_CONFIGURED`，表示“未测试”而不是 0 分；配置 `provider=http` 启用 vLLM/Jina 协议适配器，配置 `provider=tei` 启用 Hugging Face TEI 适配器。其他模型仍可实现 `RagReranker` 接口接入。已配置 Provider 但调用异常时才标记为 `FAILED`。线上普通问答保留 RRF 降级行为。

仓库提供可选 vLLM Profile，可直接满足现有 HTTP Provider 的协议：在 `.env` 中设置 `RAG_RERANKER_PROVIDER=http`，执行 `docker compose --profile rerank up -d --build`，待容器 healthy 后运行 `scripts/test-reranker.ps1`。Compose 内后端使用 `http://reranker:8000`；宿主机上的进程使用 `http://localhost:8001`。默认模型为 `BAAI/bge-reranker-v2-m3`。

若 WSL Docker 尚无可用的 NVIDIA 容器运行时，可先用 CPU 基线完成真实评测：设置 `RAG_RERANKER_PROVIDER=tei`、`RAG_RERANKER_BASE_URL=http://reranker-tei:80`，执行 `docker compose --profile rerank-cpu up -d reranker-tei`，再运行 `scripts/test-reranker.ps1 -Provider tei`。默认模型 `BAAI/bge-reranker-base` 支持中英文；它适合验证收益方向，不应用其 CPU 延迟代表最终 GPU 部署延迟。

## 一、准备 Gold 题集

1. 新建一个固定知识库，导入准备评测的文档。
2. 复制 docs/evaluation/rag-gold-dataset.example.json，开发集设置 `datasetRole=development,frozen=false`；最终验收集设置 `datasetRole=test`，人工复核完成后才设置 `frozen=true`。
3. 环境固定时可将 `knowledgeBaseBinding` 设为 `dataset` 并填写真实 `kbId`；可移植测试集则设为 `runtimeParameter`、保持 `kbId: null`，正式运行时必须显式传入 `-KnowledgeBaseId`。两种方式都会在报告中记录有效 UUID 和知识库快照 Hash。
4. 人工逐题复核 query、sourcePaths、expectedDocumentIds 以及 expectedKeywords/expectedConcepts，固定 `review.sourceEvidence` 中每份来源的 SHA-256；测试集还必须用 `review.developmentDatasetEvidence` 固定开发集文件及 Hash。创建新一代盲测集时，再用 `review.priorDatasetEvidence` 固定已经曝光的历史测试集，并设置 `nearDuplicateThreshold` 同时拦截完全重复与高相似改写。完成语义复核后才能将 `annotationStatus` 从 `DRAFT` 改为 `REVIEWED`。Runner 会把全部 `sourcePaths` 转换为稳定 `expectedSourceKeys`；它们是可接受的替代来源，命中任意一个即满足来源维度，避免把某次导入产生的 UUID 或第一份文件当成唯一文档真值。
5. 正式题集建议至少 30～50 题，并覆盖自然语言改写、错误码/类名/版本号、多文档关联、近义概念及无答案问题；无答案题使用 `"expectedNoAnswer": true`。

`rag-gold-v1.json` 已用于 Bad Case 调优，明确作为开发集；`rag-test-v1.json` 已完成复核，但运行结果也已用于失败题优化，因此只保留作历史回归集，不能继续声称为未见测试集。`rag-test-v2.json` 是重新设计的 40 道可回答题加 10 道无答案题盲测集，项目所有者已确认完成逐题人工复核并设置为 `REVIEWED/frozen=true`；复核记录见 `rag-test-v2-review.md`。v2 同时固定开发集和已曝光 v1 的 Hash，并启用近重复拦截。它采用 `runtimeParameter` 绑定知识库，不保存某台环境的临时 UUID；正式脚本强制要求 `-KnowledgeBaseId`，并会重新计算来源文件哈希，复核后文档发生变化也会拒绝运行。

## 二、运行对照评测

先登录系统取得 JWT，然后在 PowerShell 中执行：

~~~powershell
$env:JMINDOPS_TOKEN = "替换为登录后取得的 JWT"
./scripts/run-rag-evaluation.ps1 -DatasetPath ./evaluation-data/rag-gold-v1.json
~~~

默认通过 http://localhost:3000/api 调用接口，并只运行题集中列出的模式。当前未部署 Reranker 时建议只保留 VECTOR、HYBRID_RRF。其他环境可以在一行中指定参数：

~~~powershell
./scripts/run-rag-evaluation.ps1 -DatasetPath ./evaluation-data/rag-gold-v1.json -ApiBaseUrl http://localhost:8080/api -TopK 5 -Modes VECTOR,HYBRID_RRF
~~~

结果写入本地 evaluation-results：

- JSON 证据包：保留原始接口结果、逐题来源、题集哈希、Git 状态和运行参数；
- Markdown：生成所选模式对照表以及每种模式最多十条未命中样本。

接口结果还记录 evaluationId、知识库快照哈希、Embedding/Reranker 模型，以及包含候选数、证据门限和超时的完整检索配置与 Hash。脚本在落盘前会从逐题明细复算 HitRate、Expectation Recall、Proxy Precision、MRR 与 P50/P95；聚合值不一致时直接终止，不生成可误用的正式报告。

内部题集只有关键词标签时，接口会明确返回 `relevanceLabelType=KEYWORD_PROXY`。带 `sourcePaths` 的冻结题集返回 `SOURCE_KEY_AND_KEYWORD`；多个来源 key 按“任一可接受”而不是“必须全部召回”评分。题集使用 `expectedConcepts` 时，Runner 取每个别名组中经过证据校验的第一个别名作为检索证据词，不会把同一概念的多个同义表达重复计数。当来源与关键词同时存在时，一个 chunk 必须既来自任一标注文件又命中至少一个证据词项才计入 Hit/MRR/Precision，避免长文档中的无关 chunk 仅凭文件名得分。标准分级相关性结论仍使用带官方 qrels 的 T2Ranking 评分结果。

evaluation-data 和 evaluation-results 默认被 Git 忽略，避免把私有题集、知识库 ID 或内部切片提交到仓库。

## 三、公平实验要求

每组实验必须固定同一知识库快照、Gold 题集、Embedding 模型、Top-K 和运行环境。JSON 中的 `knowledgeBaseSnapshot` 或 `datasetSha256` 变化时，两次结果不能直接宣称为同条件 A/B。

正式验收使用 `run-formal-rag-evaluation.ps1`：默认预热一次，连续运行三次检索和三次端到端回答，再校验题集 Hash、知识库快照、检索配置、工作区指纹和 Agent 快照一致性。质量指标保留最小/中位/最大值，延迟报告三次中位数。若修改切片策略或重新生成向量，应建立新的知识库版本，避免新旧向量混用。

需要脚本自动创建隔离数据库、知识库和 Agent 时，运行 `start-rag-formal-evaluation.ps1`。它默认从 `D:\project\smartship\.api_key` 读取 `RELAY_API_KEY`、`RELAY_BASE_URL`、`RELAY_CHAT_MODEL`，密钥不会进入日志或 manifest；如需本地生成可传 `-ChatProvider ollama`。Embedding 仍使用本地 Ollama，Reranker 使用 8001 端口的 vLLM。

修复后只验证历史失败题时传入 `-RegressionCaseIds rag-test-018,rag-test-027`。冻结集回归的 manifest 标记为 `runType=REGRESSION`；开发集同时传入 `-Development`，标记为 `runType=DEVELOPMENT_REGRESSION` 并显式允许 DRAFT。两种模式都只运行指定题并记录 `qualityGatePassed`、`casePassRate` 和失败题号，不能替代完整正式评测。

~~~powershell
./scripts/run-formal-rag-evaluation.ps1 `
  -AgentId "<rag-agent-uuid>" `
  -KnowledgeBaseId "<reviewed-kb-uuid>" `
  -DatasetPath ./evaluation-data/rag-test-v2.json
~~~

v2 当前已通过 `annotationStatus=REVIEWED,frozen=true` 正式门禁。首次正式运行后不得依据结果修改实现再继续把同一题集称为盲测集；需要优化时将 v2 转作回归集，并另建 v3。

## 四、接口

单模式批量评测：

~~~http
POST /api/rag/evaluate/batch
~~~

请求增加 mode 和 topK，未提供时分别默认为 HYBRID_RRF 和 3。

多模式对照：

~~~http
POST /api/rag/evaluate/compare
~~~

只读取知识库快照和完整检索配置（端到端回答 Runner 会在运行前后各读取一次）：

~~~http
GET /api/rag/evaluation/provenance/{kbId}
~~~

请求示例：

~~~json
{
  "kbId": "knowledge-base-uuid",
  "modes": ["VECTOR", "HYBRID_RRF", "HYBRID_RERANK"],
  "topK": 3,
  "testCases": [
    {
      "query": "PostgreSQL 如何进行向量检索？",
      "expectedDocumentIds": ["document-uuid"],
      "expectedKeywords": ["pgvector"]
    }
  ]
}
~~~

每个模式返回 status、successful、error、HitRate@K、Recall@K、Precision@K、MRR、No-answer Accuracy、P50/P95 和逐题明细。正向检索指标只以可回答题为分母，无答案题单独计分。status 可取 COMPLETED、NOT_CONFIGURED、FAILED。

## 五、如何写进简历

只有完成真实题集运行后，才能把数字写进简历。推荐表述：

> 构建 N 道人工标注 Gold 题集，对纯向量、RRF 混合检索和 Reranker 进行同条件 A/B；以 HitRate@K、Recall@K、MRR 和 P95 量化质量—延迟权衡，并基于 Bad Case 调整检索策略。

不要使用示例题集或单元测试中的数字充当真实效果。
