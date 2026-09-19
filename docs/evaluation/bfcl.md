# JMindOps 接入 Berkeley Function Calling Leaderboard

BFCL 用来测模型是否能根据函数声明正确地选择工具、填写参数、并行调用和完成多轮工具交互。它补充 JMindOps 自建 AgentEval，但不替代后者：

| 评测 | 主要回答的问题 | 不覆盖的内容 |
| --- | --- | --- |
| BFCL V4 | 底层模型能否正确生成工具调用 | JMindOps 路由、权限、审批、Trace 与任务恢复 |
| JMindOps AgentEval | 应用运行时是否选择正确能力并遵守治理约束 | 与公开排行榜可比的模型函数调用能力 |
| T2Ranking / RAG Eval | 检索与生成链路质量 | 通用工具调用能力 |

因此 BFCL 的官方分数必须单独报告，不能与当前 20 条业务 AgentEval 合并成一个“总准确率”。

## 1. 固定版本与数据

仓库固定 `bfcl-eval==2026.3.23`。`bfcl-release.json` 记录上游源码提交、wheel SHA-256 和许可证；`requirements-bfcl.txt` 固定安装版本。运行器还会：

1. 校验实际安装版本；
2. 检查固定 case id 确实存在于该版本内置的官方数据；
3. 记录用到的官方数据文件哈希、子集哈希、Git HEAD/dirty 状态和完整命令；
4. 调用上游 `bfcl_eval generate` 与 `bfcl_eval evaluate`，不自行重写评分规则。

固定快速基线在 `evaluation-data/bfcl-v4-core-v1.json`，共 28 题：20 条单轮函数调用题和 8 条多轮题。它是官方题目的固定子集，运行时必须使用 `--partial-eval`；其结果只能称为“BFCL V4 固定子集结果”，不能宣称为官方全量排行榜成绩。

## 2. 在 WSL 安装独立环境

上游包当前固定了 `numpy==1.26.4`。项目所在 Windows 的 Python 3.13 和 WSL 默认 Python 3.14 都不适合安装，应使用 Python 3.10～3.12。不要把 BFCL 装进正在运行 reranker 的 vLLM 环境，以免改变其依赖。

本机已经存在 Python 3.11 基础解释器时，可在 PowerShell 执行：

~~~powershell
wsl -d Ubuntu -- bash -lc "cd /mnt/d/JavaWebnew/JMindOps && BFCL_BASE_PYTHON=/home/thehe/.venvs/jmindops-vllm/bin/python BFCL_VENV_PATH=/home/thehe/.venvs/jmindops-bfcl bash scripts/setup-bfcl-wsl.sh"
~~~

安装脚本先安装仅供评测器依赖使用的 CPU 版 Torch，再下载固定 BFCL wheel、校验 `3bb6dfa5...d853d3a0a`，最后安装到独立虚拟环境。它还固定补装 `soundfile==0.13.1`：当前上游 `qwen-agent` 在构建 BFCL 模型注册表时会导入该模块，但发布依赖没有覆盖它。模型推理由外部 vLLM 完成，因此 BFCL 环境不重复安装一整套 CUDA runtime。其他机器只需替换 `BFCL_BASE_PYTHON` 和 `BFCL_VENV_PATH`。

## 3. 启动官方支持的本地模型

当前 JMindOps 的 `qwen2.5` 不在该 BFCL 版本的官方模型映射中，不能通过改名冒充官方配置。建议把首个可复现基线设为 `Qwen/Qwen3-1.7B-FC`；它在 BFCL 中有专用 Qwen Function Calling handler，模型体量也更适合 RTX 4060。

端口分工建议：

- `8001`：现有 BGE reranker；
- `8002`：BFCL 临时 Qwen tool-calling 服务；
- `11434`：Ollama 日常 AgentEval。

单张 4060 不建议让 reranker 和生成模型长期同时占用显存。运行 BFCL 前先停止 reranker，评测结束后再恢复。使用现有 vLLM 可执行文件启动模型：

~~~bash
VLLM_USE_V2_MODEL_RUNNER=0 \
VLLM_USE_FLASHINFER_SAMPLER=0 \
/home/thehe/.venvs/jmindops-vllm/bin/vllm serve Qwen/Qwen3-1.7B \
  --served-model-name Qwen/Qwen3-1.7B \
  --host 0.0.0.0 \
  --port 8002 \
  --gpu-memory-utilization 0.85 \
  --max-model-len 26000 \
  --max-num-seqs 2
~~~

`--served-model-name` 必须与 BFCL 配置中的真实模型名一致。BFCL 的 `Qwen/Qwen3-1.7B-FC` 是评测注册名，实际发给 OpenAI-compatible endpoint 的模型名是 `Qwen/Qwen3-1.7B`。

本机 WSL 没有 CUDA Toolkit 的 `nvcc`，因此关闭需要即时编译的 FlashInfer sampler；RTX 4060 在 `gpu-memory-utilization=0.85` 下不足以分配 32K KV Cache，实测安全窗口约为 26K。固定子集最大输入为 18,138 tokens，未被该窗口截断；全量 long-context 结果不能据此假设同样不受影响。

## 4. 运行固定子集

在 WSL 中执行：

~~~bash
cd /mnt/d/JavaWebnew/JMindOps
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --model Qwen/Qwen3-1.7B-FC \
  --endpoint http://127.0.0.1:8002/v1
~~~

### 使用自定义 OpenAI-compatible Responses API

`--api-config` 接受仓库外的 `KEY=VALUE` 文件，且与 `--endpoint` 互斥：

~~~dotenv
RELAY_API_KEY=...
RELAY_BASE_URL=https://relay.example.com
RELAY_CHAT_MODEL=provider-model-name
~~~

在本机通过 WSL 运行：

~~~bash
cd /mnt/d/JavaWebnew/JMindOps
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --api-config /mnt/d/project/smartship/.api_key
~~~

运行器通过 `scripts/bfcl-custom-api.py` 在进程内注册独立的 `custom-relay/<provider-model>-FC`，复用 BFCL 官方 OpenAI Responses 协议和官方评分器，不修改已安装的第三方包。默认启用 `strict-v1` 通用 Agent 治理策略：优先使用匹配工具、保持参数原值与有向参数顺序、绝不主动填充未显式指定的可选默认值、先解析明确的跨轮指代且只在必填参数仍缺失或仍有多个候选时追问、缺失能力时不使用语义不同的工具绕过、并列请求逐项调用，并在后续补充参数或能力后继续完整执行挂起任务。它不包含题号或标准答案特判。若要复现无治理基线，可增加 `--api-agent-policy none`。

执行前还会把“值等于 schema 默认值”的冗余可选参数规范化为省略形式，并对同一响应批次中函数名、JSON 参数均相同的调用保留第一次；不同函数或不同参数的并行调用不受影响。前者减少严格校验器对“显式默认值”和“省略默认值”的差异判断，后者用于抑制 Agent 对同一幂等任务的重复规划与执行。

密钥仅通过子进程环境注入，不写入命令、manifest 或结果文件；报告会保留供应商模型名和脱敏后的 Base URL。自定义 Relay 结果必须标注为“BFCL 官方题集/评分器上的自定义 API 模型结果”，不能冒充 BFCL 已注册的官方模型或排行榜成绩。API 调用可能产生费用，建议先用 `--categories simple_python` 做 smoke test。

API 单次请求默认超时 120 秒，瞬时错误最多由 SDK 重试 2 次；可用 `--api-timeout-seconds` 和 `--api-max-retries` 调整。手动中断时 manifest 会记录为 `INTERRUPTED`，不会继续保持误导性的 `RUNNING` 状态。

复杂多轮 Agent 评测默认向 Responses API 发送 `reasoning.effort=high`，可通过 `--api-reasoning-effort provider-default|low|medium|high|xhigh` 调整。中转服务若不接受该字段，使用 `provider-default`。参数格式见 [OpenAI Responses API](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)。

BFCL 上游生成器会捕获单题推理异常后继续评分。运行器会在评分后再次扫描结果文件；一旦发现 `Error during inference` 或 traceback，manifest 将记录 `COMPLETED_WITH_ERRORS`、失败 case id 和脱敏错误摘要，并以非零状态退出，避免将传输失败误报为有效基线。

针对历史失败项的 6 题回归：

~~~bash
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --api-config /mnt/d/project/smartship/.api_key \
  --subset-manifest evaluation-data/bfcl-v4-api-regression-v1.json
~~~

先做无推理 dry-run，可验证版本、case id、数据哈希和最终命令：

~~~bash
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --model Qwen/Qwen3-1.7B-FC \
  --endpoint http://127.0.0.1:8002/v1 \
  --dry-run
~~~

默认输出到 `evaluation-results/bfcl/bfcl-时间戳/`：

- `result/`：官方生成器保存的逐题模型响应；
- `score/`：官方评分器生成的 CSV 和 Bad Case；
- `test_case_ids_to_generate.json`：交给官方 CLI 的固定 ID 清单；
- `jmindops-run-manifest.json`：版本、哈希、模型、命令与运行状态证据。

只跑部分能力时使用逗号分隔的 category：

~~~bash
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --categories simple_python,multiple,parallel,parallel_multiple,irrelevance \
  --endpoint http://127.0.0.1:8002/v1
~~~

如果生成中断，可以保留同一运行目录，修复后显式使用 `--run-root`、`--allow-overwrite` 和 `--mode generate`；只有已有完整生成结果时才能使用 `--mode evaluate --run-root ...`。

## 5. 全量评测边界

全量模式必须显式列出 category，防止无意运行需要 SerpAPI、联网网页或 Memory backend 的 Agentic 题：

~~~bash
/home/thehe/.venvs/jmindops-bfcl/bin/python scripts/run-bfcl-evaluation.py \
  --full \
  --categories simple_python,multiple,parallel,parallel_multiple,irrelevance \
  --endpoint http://127.0.0.1:8002/v1
~~~

只有完整运行官方计分 category、固定模型与推理参数，并保留原始 result/score 后，才适合与 BFCL 同协议结果横向比较。Web Search 类别还需要外部搜索服务，会产生联网依赖和可能的费用，本仓库默认不启用。

## 6. 面试中的正确表述

完成固定子集后可以写：

> 在应用内业务 AgentEval 之外，引入 BFCL V4 官方函数调用题集与官方评分器；固定上游版本、源码提交、题目 ID 和数据哈希，分别评估单轮选工具/参数生成、并行调用和多轮工具交互，并将模型能力分数与应用层审批、权限和恢复指标分开报告。

不要在尚未完成全量协议时写“BFCL 官方排行榜达到 X 分”，也不要把本地 qwen2.5 的结果标成 Qwen3 或其他官方注册模型结果。
