# 实验结果与证据索引

所有数字按报告的实验日期与范围引用。阶段测试存在重叠，不把 70、73 和 156 相加；48 个场景样本和 7 个业务负对照已经计入 156 项后端测试。

## 三项改造的证据

| 改造 | 日期 | 已测结果 | 证据 |
|---|---|---|---|
| 任务追踪与恢复 | 2026-11-26 | 70 项后端测试；另有 1 条浏览器验收路径；8 并发同请求仅一名执行者 | [最终阶段报告](../project-backend/verification/task-recovery-final-2026-11-26.json) |
| 预览确认闭环 | 2026-11-26 | 23 项订单确认测试及 50 项回归，共 73；另有 1 条浏览器验收路径 | [阶段报告](../project-backend/verification/order-confirmation-2026-11-26.json) |
| 工程评价体系 | 2026-11-27 | 两轮相同配置：每轮 48/48 场景样本、156/156 后端测试、12/12 报告器测试 | [可读报告](../project-backend/verification/agent-evaluation-2026-11-27.md)、[完整结果](../project-backend/verification/agent-evaluation-2026-11-27.json)、[同配置基线](../project-backend/verification/agent-evaluation-baseline-2026-11-27.json) |

第三项的最后两轮使用 MySQL 8.0.46，应用 V1–V11，前端类型检查及生产构建通过，临时实例停止并清理；后端无失败、错误或跳过。旧报告中的部署状态是当时的阶段记录，不是对当前环境的持续监测结论。

## 156 项后端测试构成

| 套件 | 数量 |
|---|---:|
| AiRequestIdentityTest | 14 |
| CartRequestRetryTest | 8 |
| NativeToolPolicyTest | 2 |
| AiGroundingValidatorTest | 8 |
| CartToolGovernanceTest | 1 |
| CartOperationMysqlIT | 6 |
| AgentRunMysqlIT | 14 |
| AgentRecoveryMysqlIT | 21 |
| OrderConfirmationMysqlIT | 23 |
| BusinessIntegrityOracleIT | 7 |
| EvaluationModelDriverTest | 4 |
| AgentBusinessEvaluationIT | 48 |
| **合计** | **156** |

12 项 Python 报告器测试另计。断线恢复、金额核对等指标分别有适用分母，见 [指标定义](agent-engineering-evaluation.md)。

## 评测发现的实际问题

初次探索时，“库存不足”场景 3 个样本均被事实校验器错误拒绝：CartTool 明确返回业务失败原因，但校验器只读取成功工具结果。数据库没有发生加购写入。

修复后，将明确的购物车业务拒绝纳入回复校验依据，同时保持价格、具体库存数、其他商品状态不能由该拒绝推导。新增 4 项定向回归，最后两轮该场景均 3/3 通过。[初次失败证据](../project-backend/verification/agent-evaluation-discovery-2026-11-27.json)保留了失败码、输入与执行状态。

探索过程中评分器和夹具也有完善，初次失败报告与最终报告不构成严格同口径的总体性能对比；最后两轮才使用相同比较键。该比较证明指定工程场景的结果可重复，不证明生产可靠性上界或模型能力提升。

## 浏览器证据

以下来自独立测试环境和合成数据，夹具使用测试身份及可控模型回复，不是生产 JWT/真实模型全链路测试。

| 路径 | 截图 |
|---|---|
| 原步骤恢复、刷新后结果 | [恢复记录](../project-backend/verification/recovery-browser-step.jpg)、[购物车核对](../project-backend/verification/recovery-browser-cart.jpg) |
| 预览、确认响应丢失后查回、刷新 | [确认前](../project-backend/verification/order-preview-browser.jpg)、[查回订单](../project-backend/verification/order-confirmed-browser.jpg)、[重复查看](../project-backend/verification/order-replay-browser.jpg) |

## 复现与解释限制

- 固定场景、脚本和归档 JSON 随仓库提供；原始 Maven/JUnit 日志保留在当时本机 target 目录，未上传完整运行目录。
- JSON 中的本机路径是实验来源记录，不能作为其他机器上的可点击证据；新运行会生成新的目录和 runId。
- sourceHashes 记录当时文件原始字节。换行符转换、后续代码修改可能改变摘要，历史报告不应被改写成当前通过记录。
- scripted 模式注入工具计划，实际业务执行真实；live 模式本次未测量，不能声称自然语言任务成功率 100%。
- 前端构建通过不等于浏览器交互通过，模拟支付不等于真实支付，局部故障实验不等于生产压测。

复现命令与依赖见 [运行指南](running.md)。本次 2026-11-28 整理文档没有重新执行或修改上述业务实验结果。
