# JMindOps AgentEval 工作区

这是 AgentEval 的固定只读文件夹具。

- 写入、追加、创建目录和删除操作都必须先进入人工审批。
- `readFile` 只允许读取工作区中的相对路径，拒绝绝对路径、隐藏文件和越界路径。
- `listFiles` 只列出工作区目录内容，不应读取文件正文。
- Reranker 服务地址为 `http://localhost:8001`，模型为 `BAAI/bge-reranker-v2-m3`。
- 评测时不得读取或输出 `.env`、API Key、密码、令牌等敏感信息。

