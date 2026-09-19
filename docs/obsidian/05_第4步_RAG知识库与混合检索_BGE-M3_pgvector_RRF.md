# 05 - 第 4 步：RAG 向量知识库与混合检索（AST 分块 + BGE-M3 + BM25 + RRF）

#JMindOps #RAG #Embedding #pgvector #BM25 #RRF #HybridSearch

> [!NOTE] 
> 目标：构建工业级 RAG 检索系统。解决固定字数切片导致的 Markdown 表格截断问题，以及单一向量检索在精准专有名词、错误码匹配上的缺陷。

---

## 1. 为什么单一向量检索会翻车？

| 检索模式 | 擅长场景 | 缺陷场景 |
| :--- | :--- | :--- |
| **纯向量检索** | 语义同义表达（“重置凭据” $\approx$ “修改密码”） | **精准专有名词/错误码**（如 `40102` 与 `40101` 在向量空间极度相似，容易排错） |
| **BM25 倒排检索** | 精确型号、错误码、函数名及多词相关性排序 | **同义不同字**（文档写“修改密码”，用户搜“重置凭据”，仍可能不命中） |
| **混合检索 + RRF** | **结合两者长处，既懂语义又死磕专有名词** | 需要设计排名融合算法 |

---

## 2. AST 语义解析与表格保护 (`MarkdownParserServiceImpl.java`)

使用 **Flexmark** 解析 Markdown 抽象语法树（AST），按标题切分章节，并在遇到表格节点 `TableBlock` 时保留原生排版：

```java
@Service
public class MarkdownParserServiceImpl implements MarkdownParserService {
    private final Parser parser;

    public MarkdownParserServiceImpl() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    private void extractSections(Document document, List<MarkdownSection> sections) {
        // 按 Heading 提取标题，收集当前标题到下一标题间的所有正文与完整 TableBlock
        // ...
    }
}
```

---

## 3. 混合检索与 RRF（倒数排名融合）算法

### 📐 RRF 算分公式：
$$\text{Score}(d) = \sum_{m \in M} \frac{1}{k + r_m(d)} \quad (k=60)$$

- $r_m(d)$ 为文档 $d$ 在第 $m$ 路检索（向量路 / 关键词路）中的名次（1-based）；
- $k=60$ 为平滑常数，防止头名分值过大。

### 🧮 算分实例对照表：

| 候选切片 | 向量路排名 | 关键词路排名 | RRF 综合算分 | 最终排名 |
| :--- | :---: | :---: | :--- | :---: |
| **切片 A** | 第 1 名 | 未命中 (0分) | $\frac{1}{60+1} + 0 = \mathbf{0.01639}$ | 🥉 第 3 名 |
| **切片 B** | 第 2 名 | 第 1 名 | $\frac{1}{60+2} + \frac{1}{60+1} = 0.01612 + 0.01639 = \mathbf{0.03251}$ | 🥇 **第 1 名 (两路认可！)** |
| **切片 C** | 第 3 名 | 第 2 名 | $\frac{1}{60+3} + \frac{1}{60+2} = 0.01587 + 0.01612 = \mathbf{0.03199}$ | 🥈 第 2 名 |

### 💻 核心实现 (`RagServiceImpl.java`)
```java
@Override
public List<RagSource> hybridSearchWithSources(String kbId, String query) {
    // 1. 向量路 Top 3
    String queryEmbedding = toPgVector(doEmbed(query));
    List<ChunkBgeM3> vectorChunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);

    // 2. Jieba + BM25 倒排路 Top 5
    List<ChunkBgeM3> bm25Chunks = chunkBgeM3Mapper.bm25Search(kbId, query, 5);

    // 3. RRF 倒数排名融合
    List<String> orderedContents = reciprocalRankFusion(vectorChunks, bm25Chunks);

    // 4. 可选 Reranker 进一步精排...
    return orderedContents.stream()
            .limit(3)
            .map(uniqueChunks::get)
            .map(chunk -> new RagSource(chunk.getDocId(), chunk.getContent()))
            .toList();
}

private void addRankScores(Map<String, Double> scores, List<ChunkBgeM3> rankedChunks, int rankConstant) {
    for (int index = 0; index < rankedChunks.size(); index++) {
        String content = rankedChunks.get(index).getContent();
        scores.merge(content, 1.0 / (rankConstant + index + 1), Double::sum);
    }
}
```

---

## 4. 知识库工具与引用标记 (`KnowledgeTools.java`)

```java
@org.springframework.ai.tool.annotation.Tool(
        name = "KnowledgeTool",
        description = "从指定知识库中执行相似性检索（RAG）。"
)
public String knowledgeQuery(String kbsId, String query) {
    // 1. 安全校验 1：Agent 白名单
    if (!ToolExecutionContext.isKnowledgeBaseAllowed(kbsId)) throw new BizException("无权访问该知识库");
    // 2. 安全校验 2：租户 Owner 校验
    resourceAccessService.requireOwnedKnowledgeBase(kbsId);

    // 3. 执行混合检索并格式化带 [Source N] 引用标记
    List<RagSource> sources = ragService.hybridSearchWithSources(kbsId, query);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sources.size(); i++) {
        sb.append("[Source ").append(i + 1).append(" | documentId=").append(sources.get(i).documentId()).append("]\n")
          .append(sources.get(i).content()).append("\n\n");
    }
    return sb.toString();
}
```
