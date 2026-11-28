# Agent 工程验证与业务完整性评测

- 运行：`agent-evaluation-2dc9af91fd774606bf6e0ba96254aa91`
- 模式：`scripted`；结果：**passed**
- 场景样本：48/48；后端测试：156；前端：passed
- 真实模型能力：**未测量，不把 scripted 通过率当成模型成功率。**

| 指标 | 通过 / 总数 | 比率 |
|---|---:|---:|
| 场景验收通过率 | 48 / 48 | 100.0% |
| 业务不变量通过率 | 48 / 48 | 100.0% |
| 执行记录完整率 | 48 / 48 | 100.0% |
| 必要工具覆盖率 | 48 / 48 | 100.0% |
| 恢复场景通过率 | 6 / 6 | 100.0% |
| 重复运行稳定性 | 16 / 16 | 100.0% |

场景时延 P50 / P95：213 / 491 ms（含数据库准备与判定，不是模型响应时延）。

## 逐场景结果

| 场景 | 样本 | 结果 | 不通过原因 |
|---|---:|---|---|
| read_product | 1 | 通过 | — |
| read_product | 2 | 通过 | — |
| read_product | 3 | 通过 | — |
| multi_tool_preview | 1 | 通过 | — |
| multi_tool_preview | 2 | 通过 | — |
| multi_tool_preview | 3 | 通过 | — |
| preview_existing | 1 | 通过 | — |
| preview_existing | 2 | 通过 | — |
| preview_existing | 3 | 通过 | — |
| explicit_confirmation | 1 | 通过 | — |
| explicit_confirmation | 2 | 通过 | — |
| explicit_confirmation | 3 | 通过 | — |
| cart_changed | 1 | 通过 | — |
| cart_changed | 2 | 通过 | — |
| cart_changed | 3 | 通过 | — |
| price_changed | 1 | 通过 | — |
| price_changed | 2 | 通过 | — |
| price_changed | 3 | 通过 | — |
| empty_cart | 1 | 通过 | — |
| empty_cart | 2 | 通过 | — |
| empty_cart | 3 | 通过 | — |
| insufficient_stock | 1 | 通过 | — |
| insufficient_stock | 2 | 通过 | — |
| insufficient_stock | 3 | 通过 | — |
| fabricated_order_claim | 1 | 通过 | — |
| fabricated_order_claim | 2 | 通过 | — |
| fabricated_order_claim | 3 | 通过 | — |
| payment_boundary | 1 | 通过 | — |
| payment_boundary | 2 | 通过 | — |
| payment_boundary | 3 | 通过 | — |
| model_timeout | 1 | 通过 | — |
| model_timeout | 2 | 通过 | — |
| model_timeout | 3 | 通过 | — |
| committed_result_lost | 1 | 通过 | — |
| committed_result_lost | 2 | 通过 | — |
| committed_result_lost | 3 | 通过 | — |
| recover_original_step | 1 | 通过 | — |
| recover_original_step | 2 | 通过 | — |
| recover_original_step | 3 | 通过 | — |
| request_replay | 1 | 通过 | — |
| request_replay | 2 | 通过 | — |
| request_replay | 3 | 通过 | — |
| foreign_draft | 1 | 通过 | — |
| foreign_draft | 2 | 通过 | — |
| foreign_draft | 3 | 通过 | — |
| inventory_without_evidence | 1 | 通过 | — |
| inventory_without_evidence | 2 | 通过 | — |
| inventory_without_evidence | 3 | 通过 | — |

## 证据完整性

[]

## 判定规则

场景通过必须同时满足预期数据库状态、终态、回复状态、必要工具及完整 trace；业务不变量包含金额一致、库存守恒、订单与确认草稿绑定和用户隔离。缺失样本、跳过测试、来源变更或清理失败都会使总门禁失败。恢复率仅以恢复场景为分母，无适用样本时为 null。

## 范围限制

- Scripted plans are injected; they do not measure model tool selection or language understanding.
- Live mode uses a bounded chat-completions evaluation driver with production prompts/tools/core, not the production SSE transport.
- Retrieval and conversation memory are fixture substitutes; no Milvus retrieval or long-dialogue evaluation.
- Only isolated seeded MySQL data; no production JWT/real payment/load-capacity claim.
- Acceptance means expected outcomes including safe rejection, not purchase conversion.
- Latency includes fixture and scoring costs; provider token usage is null if missing.

## 基线比较

```json
{
  "baselineRun": "agent-evaluation-ea49c2b229154f90a66d02de67999475",
  "rateDelta": {
    "scenarioAcceptance": 0.0,
    "businessIntegrity": 0.0,
    "traceCompleteness": 0.0,
    "requiredTools": 0.0
  },
  "p95DurationDeltaMs": 5
}
```
