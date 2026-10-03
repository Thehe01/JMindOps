# Spring AI → LangChain4j

JMindOps 使用 LangChain4j 1.20.2 BOM；核心、OpenAI、Gemini、Ollama 模块为 1.20.2，MCP 与反应式流模块由 BOM 固定为 1.20.2-beta30。Java 17、Spring Boot 3.5.8 保持原有版本。

## 执行边界

- `com.kama.jmindops.ai.ChatClient` 是项目自有的轻量适配接口，底层调用原生 `ChatModel` / `StreamingChatModel`。模型仅返回调用请求，实际工具执行仍经过 Policy、HITL、Lease Fencing、Ledger 和 Checkpoint，不启用 AI Services 的自动工具循环。
- 本地工具改用 `dev.langchain4j.agent.tool.Tool`；Schema 和参数解析交给 `ToolSpecifications` / `DefaultToolExecutor`。执行器保留 Spring 代理和原方法参数名，并传播原始工具异常，防止失败被记为成功。
- 流式适配发布文本增量，最后一次响应携带完整工具调用和 usage；不拼接局部工具参数，不重复发布完整文本，不发布或存储原生 thinking 字段。超时会取消已获取句柄的请求；在 SDK 尚未提供句柄时，停止消费并忽略后续结果。
- MCP 使用原生 `DefaultMcpClient` 与 `StdioMcpTransport`；发现工具时不执行，调用沿用账本的稳定调用 ID。外部工具仍默认按高风险审批，协议错误抛出异常，关闭应用时关闭客户端。
- 消息、工具调用和结果采用项目自有信封，保留既有 `id/type/name/arguments`、`id/name/responseData` JSON 字段，既有数据库记录和检查点可直接读取。无需清空数据库或重建知识库。聊天窗口采用 LangChain4j 的工具结果感知淘汰规则，窗口长度按原生消息计数（每个工具结果计一条）。
- BGE-M3 HTTP Embedding、PostgreSQL/pgvector、BM25、RRF、Reranker、索引版本与原子切换原本为自有实现，继续使用原有链路；显式增加 WebFlux 依赖提供 `WebClient`。前端、业务表和数据库迁移未改变。

## 配置

AI 配置前缀从 `spring.ai.*` 改为 `app.ai.*`。API Key、RAG 和 MCP 环境变量沿用原名；不再依赖 Spring AI 自动配置。

| 功能 | 环境变量 | 默认值 / 说明 |
| --- | --- | --- |
| 聊天 Provider | `APP_AI_CHAT_PROVIDER` | `none`；按 Key 注册云模型；`ollama` 显式选择本地模型。旧 `SPRING_AI_MODEL_CHAT` 为回退 |
| DeepSeek | `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_CHAT_MODEL` | `https://api.deepseek.com/v1`, `deepseek-chat` |
| 智谱 | `ZHIPUAI_API_KEY`, `ZHIPUAI_BASE_URL`, `ZHIPUAI_CHAT_MODEL` | `https://open.bigmodel.cn/api/paas/v4`, `glm-4.6` |
| Gemini | `GOOGLE_GENAI_API_KEY`, `GOOGLE_GENAI_CHAT_MODEL` | `gemini-2.5-flash` |
| Ollama | `OLLAMA_CHAT_BASE_URL`, `OLLAMA_CHAT_MODEL` | 本地 `http://localhost:11434`, `qwen2.5`；Compose 可显式设置主机地址 |
| MCP stdio | `MCP_CLIENT_ENABLED` | `false`；沿用 `MCP_FILESYSTEM_COMMAND`, `MCP_FILESYSTEM_ROOT` |

既有 Agent 的模型键 `deepseek-chat`、`glm-4.6`、`gemini-2.5`、`ollama-qwen2.5` 保持不变。Gemini 的键是项目别名，实际模型名默认采用 `gemini-2.5-flash`。DeepSeek、智谱通过 OpenAI 兼容协议接入；旧官方 URL 会补齐 `/v1` / `/v4`，自定义网关需填写完整 API 根地址。

## 验证

```bash
cd jmindops
mvn -B -ntp verify
```

默认排除 `live` 和 `postgres-integration`，不调用付费模型。迁移回归覆盖：完整工具参数、流式文本和 usage、超时取消、工具集合清空、原始异常传播、Spring 代理调用、旧消息与检查点 JSON、聊天窗口隔离、Provider 条件注册和 MCP 调用 ID。

仓库 CI 继续执行 PostgreSQL 集成测试及 Compose 空库启动检查；真实模型验证仍需显式提供 Provider Key 并启用 `live-tests`。历史 `docs/obsidian` 笔记保留当时的 Spring AI 实现描述，当前配置与运行方式以本页及 README 为准。
