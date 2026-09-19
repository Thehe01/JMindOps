# BFCL V4 固定子集：本地模型与 API 对照基线

> 本文保留未治理 API 基线。问题修复与回归结果见 [BFCL API Agent 治理修复报告](./bfcl-api-remediation-20260909.md)。

评测日期：2026-09-09

## 评测口径

- BFCL：`bfcl-eval==2026.3.23`
- 上游提交：`6ea57973c7a6097fd7c5915698c54c17c5b1b6c8`
- 固定子集：`jmindops-bfcl-v4-core-v1`，28 题
- 题目组成：20 条单轮、8 条多轮
- 评分器：BFCL 官方 evaluator，`--partial-eval`
- API 注册名：`custom-relay/gpt-5.6-terra-FC`
- API 接口：OpenAI-compatible Responses API

这是官方题目的固定子集结果，不是 BFCL 官方全量排行榜成绩。下表的“子集微平均”按实际正确题数除以 28 计算，不使用缺失类别按零计分的 BFCL `Overall Acc` 汇总列。

## 准确率对比

| 指标 | Qwen3-1.7B 本地 | gpt-5.6-terra API | 变化 |
| --- | ---: | ---: | ---: |
| 单轮 | 19/20（95.00%） | 17/20（85.00%） | -10.00pp |
| 多轮 | 0/8（0.00%） | 5/8（62.50%） | +62.50pp |
| 固定子集微平均 | 19/28（67.86%） | 22/28（78.57%） | +10.71pp |

| Category | Qwen3-1.7B 本地 | gpt-5.6-terra API |
| --- | ---: | ---: |
| `simple_python` | 100% | 100% |
| `multiple` | 100% | 75% |
| `parallel` | 100% | 75% |
| `parallel_multiple` | 75% | 75% |
| `irrelevance` | 100% | 100% |
| `multi_turn_base` | 0% | 50% |
| `multi_turn_miss_func` | 0% | 50% |
| `multi_turn_miss_param` | 0% | 50% |
| `multi_turn_long_context` | 0% | 100% |

## 效率对比

| 指标 | Qwen3-1.7B 本地 | gpt-5.6-terra API |
| --- | ---: | ---: |
| 端到端生成耗时 | 55m56s | 28m06s |
| 模型调用次数 | 196 | 163 |
| 输入 tokens | 1,564,384 | 1,289,913 |
| 输出 tokens | 74,420 | 10,593 |
| 累计请求延迟 | 3,353.66s | 1,686.51s |
| BFCL 平均请求延迟 | 17.11s | 10.35s |
| BFCL P95 请求延迟 | 37.99s | 22.43s |

API 模型将输出 token 数降低约 85.8%，累计请求延迟降低约 49.7%，并显著改善多轮任务，但仍平均需要约 17.9 次模型调用才能完成一条多轮用例，运行时仍需增加无进展检测、重复调用熔断和模型升级路由。

## API Bad Case

- `multi_turn_base_0`：最后一轮执行结果不完整。
- `multi_turn_miss_func_1`：第 2 轮没有可评分的模型响应。
- `multi_turn_miss_param_0`：第 4 轮没有可评分的模型响应。
- `multiple_3`：模型主动填入 `rounding=2`，标准答案要求省略或使用 0。
- `parallel_3`：模型扩写了实体名称，没有保持题目中的精确 canonical value。
- `parallel_multiple_3`：题面给定周长 14、面积 15，在实数范围不存在对应矩形；模型指出矛盾并返回文本，BFCL 标准答案仍要求工具调用，因此被判错。该题应作为评测集争议项单独报告。

## 结论

API 路由解决了本地 1.7B 最突出的多轮能力短板，但不能替代 JMindOps 运行时治理。建议保留双层路由：简单单轮任务使用本地模型，多轮、错误恢复和长上下文任务升级到 API；同时在应用层实现工具参数校验、状态摘要、重复调用熔断和明确终止条件。

## 原始证据

- API run manifest：`evaluation-results/bfcl/bfcl-20260909-114041/jmindops-run-manifest.json`
- API overall：`evaluation-results/bfcl/bfcl-20260909-114041/score/data_overall.csv`
- API multi-turn：`evaluation-results/bfcl/bfcl-20260909-114041/score/data_multi_turn.csv`
- 本地 run manifest：`evaluation-results/bfcl/bfcl-20260909-000605/jmindops-run-manifest.json`
