# BFCL API Agent 治理修复报告

评测日期：2026-09-09

## 修复范围

本轮修复针对 API 基线的 6 个 Bad Case 以及评测过程中发现的运行可靠性问题，不修改 BFCL 官方题目、标准答案或已安装的第三方包，也不包含题号特判。

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 自然语言结束语解码异常 | 官方 Responses handler 将字符串按工具调用字典解码 | 自定义 handler 将自然语言完成消息规范化为空工具调用 |
| 可选参数被主动填默认值 | 模型将 schema 描述中的默认值写入参数 | 字段级约束，并在执行前省略与 schema 默认值相等的冗余可选参数 |
| 实体名称被扩写 | 模型对用户字面量做了解释性改写 | 要求保持参数字面值，不扩写或注释 |
| diff 操作数顺序颠倒 | 模型未保持有向操作语义 | 明确当前/最终对象在前、历史/参考对象在后 |
| 缺失工具时绕过 | 模型用不同工具组合模拟缺失操作 | 要求保留挂起任务，工具补充后再完整执行 |
| 缺失参数时提前执行 | 模型把比较对象推断为操作目标 | 先解析唯一跨轮指代；仍有多个候选时零调用并追问 |
| 并列请求漏调用 | 模型只完成第一个对象或属性 | 要求每个独立对象/属性都有对应调用 |
| 等价请求产生重复调用 | 模型把语义等价且参数相同的任务规划了两次 | 提示层合并等价请求；适配器按函数名和规范化 JSON 参数对同批次调用稳定去重 |
| Relay 长尾或 TLS 瞬断 | API 客户端和 runner 缺少完整运行治理 | 可配置请求超时、有限 SDK 重试和推理错误完整性扫描 |
| 单样本汇总崩溃 | BFCL 对一个延迟样本调用 `statistics.stdev` | 自定义入口仅在当前进程将单样本标准差定义为 0 |
| 失败 run 被标为完成 | BFCL 捕获单题异常后继续退出 0 | runner 扫描 result，将 run 标为 `COMPLETED_WITH_ERRORS` 并返回非零 |

复杂 Agent 评测默认使用 Responses API 的 `reasoning.effort=high`；可切换为 `provider-default`、`low`、`medium` 或 `xhigh`。官方参数定义见 [OpenAI Responses API](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)。

## 回归结果

### 历史 6 个 Bad Case

最终整组回归：6/6（100%）。

- Run：`evaluation-results/bfcl/bfcl-20260909-api-regression-final-v2`
- `multiple_3`、`parallel_3`、`parallel_multiple_3`：均通过
- `multi_turn_base_0`、`multi_turn_miss_func_1`、`multi_turn_miss_param_0`：均通过

其中争议矩形题另外做了 3 次独立稳定性运行，结果 3/3：

- `evaluation-results/bfcl/bfcl-20260909-parallel-stability-2`
- `evaluation-results/bfcl/bfcl-20260909-parallel-stability-3`
- `evaluation-results/bfcl/bfcl-20260909-parallel-stability-4`

### 28 题核心集与重复调用回归

修复后核心集运行中，20 个单轮全部通过，8 个多轮中 7 个通过；最后一题在第 4 轮遇到 Relay TLS EOF，因此该次运行的有效语义结果为 27/27，完整性为 27/28。manifest 已纠正为 `COMPLETED_WITH_ERRORS`，不能将它报告为一个完整 96.43% 基线。

- Run：`evaluation-results/bfcl/bfcl-20260909-api-core-final`
- 单轮：20/20
- 已完整执行的多轮：7/7
- 基础设施失败：`multi_turn_miss_param_1`

随后重跑缺参多轮类别，`reasoning.effort=high` 下为 2/2（100%），证明此前未完成项可正常通过，但这属于定向恢复证据，不与上述不完整 run 拼接成官方分数。

- Run：`evaluation-results/bfcl/bfcl-20260909-api-high-reasoning`

无传输错误的第二次 28 题运行于 2026-09-09 14:22:34 完成，结果为 27/28（96.43%）：单轮 19/20，多轮 8/8，唯一失败项是 `parallel_multiple_2`。模型为同一 `circle.calculate_circumference(diameter=10)` 生成了两次调用，官方评分器报 `wrong_count`。

- Run：`evaluation-results/bfcl/bfcl-20260909-api-core-high-final-v2`
- Manifest：`COMPLETED`，`inferenceErrors=0`
- 唯一 Bad Case：`parallel_multiple_2`

增加“等价请求合并”提示和同批次精确调用去重后，使用未修改的 BFCL 官方题目及答案对该失败题进行独立 API 回归，结果为 1/1（100%）。原始结果只包含一次面积调用和一次周长调用；此外单元测试覆盖模型仍返回重复项时的适配器兜底，以及不同函数、不同参数不会被误删。

- 回归清单：`evaluation-data/bfcl-v4-api-dedup-regression-v1.json`
- Run：`evaluation-results/bfcl/bfcl-20260909-api-dedup-targeted-v1`
- 推理参数：`strict-v1`、`reasoning.effort=high`、`temperature=0.001`
- 结果：`parallel_multiple_2` 1/1，`inferenceErrors=0`

## 当前结论

基线中的 6 类已知语义问题和后续重复调用问题均有独立可复现的通过记录。当前完整、无传输错误的 28 题冻结基线仍应报告为 27/28（96.43%）；修复后的 `parallel_multiple_2` 单题回归为 1/1，不能与旧 run 拼接宣称 28/28。API 方仍可能发生传输长尾或 TLS 瞬断，但现在有超时、有限重试和失败状态识别，不会再产生“伪完成”报告。

若需要更新简历中的冻结总分，应在当前代码下重新运行一次完整 28 题，并以该单一 run 的 manifest 与 score 为唯一证据。
