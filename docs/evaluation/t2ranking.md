# JMindOps 接入 T2Ranking

## 目标与边界

本适配器把 T2Ranking 官方的 collection、queries 和 qrels 转换为 JMindOps
可以直接上传和评测的固定抽样子集，用于比较 VECTOR、HYBRID_RRF 和
HYBRID_RERANK。

生成数据继承官方 qrels 标注，因此 evaluation.dataset.json 的
annotationStatus 为 REVIEWED。它仍然只是固定抽样子集，不是官方全量语料评测，
结果不能直接与 T2Ranking 官方排行榜横向比较。

T2Ranking 官方仓库：

- https://github.com/THUIR/T2Ranking
- https://huggingface.co/datasets/THUIR/T2Ranking

数据集使用 Apache License 2.0。使用或发布结果时应按官方仓库要求引用原论文。

## 1. 下载原始数据

建议将原始数据放在已被 Git 忽略的 evaluation-data 目录：

    git lfs install
    git clone https://huggingface.co/datasets/THUIR/T2Ranking evaluation-data/raw/T2Ranking

适配器会同时尝试以下两种目录布局：

    evaluation-data/raw/T2Ranking/collection.tsv
    evaluation-data/raw/T2Ranking/data/collection.tsv

dev 评测默认读取：

    collection.tsv
    queries.dev.tsv
    qrels.dev.tsv

应优先使用带分级相关性标签的 qrels.dev.tsv，而不是只有正样本的
qrels.retrieval.dev.tsv。

## 2. 生成可复现子集

Python 脚本只使用标准库。默认选择 100 个问题、5000 个段落和 5 个 Markdown
分片：

    python scripts/prepare-t2ranking.py \
      --data-dir evaluation-data/raw/T2Ranking \
      --output-dir evaluation-data/t2ranking-dev-q100-c5000 \
      --query-count 100 \
      --corpus-size 5000 \
      --shards 5 \
      --seed 2026 \
      --top-k 10

Windows PowerShell 中可以写成一行：

    python scripts/prepare-t2ranking.py --data-dir evaluation-data/raw/T2Ranking --output-dir evaluation-data/t2ranking-dev-q100-c5000 --query-count 100 --corpus-size 5000 --shards 5 --seed 2026 --top-k 10

输出目录结构：

    t2ranking-dev-q100-c5000/
      corpus/
        t2ranking-dev-001.md
        ...
      evaluation.dataset.json
      manifest.json

manifest.json 保存随机种子、抽样参数、官方 qrels、分片 SHA-256 和语料
fingerprint。输出目录已存在时脚本会拒绝覆盖，避免破坏评测溯源。

每个 passage 会写入唯一的 T2_PID 标记。该标记只用于把 JMindOps 返回的 chunk
映射回官方 passage id，不参与查询，也不充当相关性判断来源。

## 3. 导入 JMindOps

启动 PostgreSQL、Redis、JMindOps 和 BGE-M3 embedding 服务，登录后取得 JWT：

    $env:JMINDOPS_TOKEN = "登录后取得的 JWT"
    ./scripts/import-t2ranking.ps1 -PreparedDirectory ./evaluation-data/t2ranking-dev-q100-c5000 -ApiBaseUrl http://localhost:8080/api

导入脚本需要 PowerShell 7。它会：

1. 校验所有 corpus shard 的 SHA-256；
2. 创建独立的 T2Ranking 知识库；
3. 上传并索引 Markdown 分片；
4. 把真实 kbId 写入 evaluation.ready.json；
5. 生成不包含 JWT 的 import-result.json。

如需导入已有知识库：

    ./scripts/import-t2ranking.ps1 -PreparedDirectory ./evaluation-data/t2ranking-dev-q100-c5000 -KnowledgeBaseId "已有知识库 UUID"

上传接口限制为每用户每分钟 6 次。默认生成 5 个分片不会触发该限制；自定义更多
分片时脚本会在收到 429 后按 Retry-After 重试。

## 4. 运行 JMindOps 对照评测

    ./scripts/run-rag-evaluation.ps1 \
      -DatasetPath ./evaluation-data/t2ranking-dev-q100-c5000/evaluation.ready.json \
      -ApiBaseUrl http://localhost:8080/api \
      -Modes VECTOR,HYBRID_RRF \
      -TopK 10

已启动 vLLM Reranker 时可以增加 HYBRID_RERANK；未启动时不要把
NOT_CONFIGURED 解释为 0 分。

## 5. 复算 T2Ranking 分级指标

JMindOps 原始报告使用二值 HitRate、Recall、Precision 和 MRR。T2Ranking qrels
包含分级相关性，因此评测完成后还应运行：

    python scripts/score-t2ranking.py \
      --manifest evaluation-data/t2ranking-dev-q100-c5000/manifest.json \
      --result evaluation-results/rag-evaluation-时间戳.json

脚本根据返回 chunk 中的 T2_PID 标记复算：

- MRR；
- nDCG@K；
- Recall@K；
- Precision@K；
- 无法映射回 T2 passage 的来源数量。

它会在原始结果旁生成 -t2ranking.json 和 -t2ranking.md。

## 6. 公平对比要求

对比不同检索模式时必须固定：

- seed、query-count、corpus-size 和 relevance-threshold；
- manifest 中的 corpusFingerprint；
- 知识库快照；
- Embedding 模型、Top-K 和 Reranker 模型；
- 相同预热策略与重复次数。

推荐先用 100 问、5000 段落完成链路验证，再逐步扩大到 1 万或更多段落。只有把
完整 collection 按官方协议索引并使用官方评测脚本时，结果才可以称为
T2Ranking 官方全量成绩。
