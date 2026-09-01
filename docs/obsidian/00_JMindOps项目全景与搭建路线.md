# 00 - JMindOps 项目全景与搭建路线图 (MOC)

#JMindOps #AI_Agent #SpringAI #React19 #RAG #系统架构

> [!NOTE] 
> 本套笔记为 **JMindOps 企业级 AI Agent 应用** 从零到一的完整搭建教程，涵盖后端 ReAct 循环、多模型动态装配、RAG 混合检索、AOP 工具审批治理、SSE 流式推流、JWT 安全鉴权与 React 19 前端工程。

---

## 🗺️ 搭建路线导航 (Map of Content)

- [[01_第0步与第1步_环境配置_pgvector与持久化层|第 0 & 1 步：环境配置、pgvector 向量支持与数据持久化层]]
- [[02_第2步_Agent核心演进与ReAct决策循环|第 2 步：Agent 核心演进（V1 对话 -> V2 ReAct 循环 -> JMindOps 瞬态运行时）]]
- [[03_第3步_意图路由与问题改写_RouterAgent|第 3 步：意图路由与问题改写（RouterAgent + Intent 分类）]]
- [[04_第3步扩展_动态工厂设计与运行时装配|第 3 步扩展：JMindOpsFactory 动态工厂深度剖析与无状态设计]]
- [[05_第4步_RAG知识库与混合检索_BGE-M3_pgvector_RRF|第 4 步：RAG 向量知识库系统（AST 解析 + BGE-M3 + 混合检索 + RRF 融合）]]
- [[06_第5步_工具生态与AOP人机协同审批治理|第 5 步：工具生态与 AOP 人机协同审批治理（@RequiresToolApproval + 参数指纹）]]
- [[07_第6步_事件驱动架构与SSE流式推流|第 6 步：事件驱动架构与 SSE 流式推流（SseEmitter + ConcurrentHashMap + Single-flight）]]
- [[08_第7步_JWT安全鉴权与RESTful接口暴露|第 7 步：JWT 安全鉴权与 RESTful API 暴露（Spring Security + 租户防水平越权）]]
- [[09_第8步_React19前端交互与流式打字机实现|第 8 步：React 19 前端页面与流式打字机交互实现（fetch-stream + 状态机 + 历史对账）]]

---

## 🏗️ 整体技术架构图

```mermaid
flowchart LR
  UI[React 19 + Ant Design] -->|REST /api| API[Spring Boot 3 API]
  UI -->|SSE /sse| Stream[SSE Service (SseEmitter)]
  API --> DB[(PostgreSQL 15 + pgvector)]
  API --> Event[ChatEvent]
  Event --> Router[Query Rewriter + RouterAgent]
  Router --> Factory[JMindOpsFactory 动态装配]
  Factory --> Agent[JMindOps ReAct Agent Loop]
  Agent --> Tools[Knowledge / Mail / DB / MCP Tools]
  Agent --> Model[DeepSeek / GLM / Gemini]
  Agent --> Result[Agent Message Events]
  Result --> DB
  Result --> Stream
  Tools --> Embedding[BGE-M3 Embedding + RRF Reranker]
```

---

## 🛠️ 技术栈总览

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **后端框架** | Java 17, Spring Boot 3.5.8, Spring AI 1.1.0 | 现代化企业级 AI 开发底盘 |
| **持久层** | MyBatis 3.0.3, PostgreSQL 15, pgvector, Flyway | 关系型业务数据 + 1024 维语义向量存储 |
| **大模型生态** | DeepSeek-V3/R1, 智谱 GLM-4, Google Gemini-2.5 | 多模型适配与运行时动态路由 |
| **本地 RAG** | BGE-M3 Embedding, Flexmark AST, RRF 倒数融合 | 结构化切片与高召回混合检索 |
| **推流与通信** | SSE (Server-Sent Events), Spring Event 总线 | 异步解耦，高并发流式打字输出 |
| **安全治理** | Spring Security, JWT (HMAC-SHA256), Spring AOP | 租户数据强隔离，敏感工具人机审批 |
| **前端工程** | React 19, TypeScript, Vite, Ant Design, Tailwind CSS | 单页流式客户端，状态机与断线重连对账 |
