# 第三项改造：Agent 工程验证与业务完整性评价体系

## 问题、方法与效果

前两项改造已有任务恢复、订单确认和数据库测试，但测试分散，缺少统一的任务场景、跨表判定及可比较报告。接口返回成功、模型声称成功，均不能证明实际商品、金额、库存和用户归属正确。

本次建立 `固定场景 → 真实业务工具/事务 → SQL 独立核对 → trace 核对 → 验收门禁 → JSON/Markdown 报告` 链路。通过率表示预期行为得到满足；安全拒绝、超时和中断恢复也有明确的期望状态，不要求所有模型任务都标记为 SUCCEEDED。

首次运行发现了可重现的产品缺陷：CartTool 返回“商品库存不足”，但校验器只读取成功工具结果，因此正确的失败说明被误拒为 GROUNDING_REJECTED。修复后，最终回复与流式回复均可引用明确的购物车业务拒绝；这些失败结果仍不会成为“成功工具事实”，也不能推导库存为零、具体库存数、售价或其他商品的状态。

最终测量结果见仓库 `project-backend/verification/agent-evaluation-2026-11-27.json` 及同名 Markdown。完整报告保留每个样本的输入、预期值、实际数据库状态、工具参数、任务/步骤、评分失败码、源码摘要、测试计数和临时数据库清理结果。

## 文件位置与职责

| 文件（相对仓库根目录） | 职责 |
|---|---|
| `project-backend/src/test/resources/evaluation/shopping-scenarios.json` | 版本化的 16 个场景、用户请求、初始购物车、工具计划及预期业务结果；6 个场景允许真实模型模式。 |
| `project-backend/src/test/java/com/example/service/ai/impl/AgentBusinessEvaluationIT.java` | 参数化执行与重复采样；调用生产编排核心、工具回调、审计和恢复服务；保存逐场景证据。 |
| `project-backend/src/test/java/com/example/service/ai/impl/AgentEvaluationFixture.java` | 两商品、两用户的固定数据；接真实 MySQL、MyBatis、Spring 事务。仅记忆和检索召回为替身。 |
| `project-backend/src/test/java/com/example/service/ai/impl/BusinessIntegrityOracle.java` | 根据数据库独立核对金额、商品、数量、库存、确认绑定和用户隔离。 |
| `project-backend/src/test/java/com/example/service/ai/impl/BusinessIntegrityOracleIT.java` | 7 个负对照，故意制造数据库异常，验证评分器能够拒绝。 |
| `project-backend/src/test/java/com/example/service/ai/impl/EvaluationModelDriver.java` | 可选真实模型驱动，使用生产 prompt/工具定义/回调；限制轮次、工具调用和 HTTP 时长。 |
| `project-backend/src/test/java/com/example/service/ai/impl/EvaluationModelDriverTest.java` | 4 项传输契约测试：工具结果回传、未知工具拦截、异常响应、调用预算。 |
| `project-backend/scripts/run-agent-evaluation.ps1` | 单命令入口；检查报告器、前端构建、隔离数据库与后端回归，最后生成报告，以非零退出码阻止失败结果通过。 |
| `project-backend/scripts/test-cart-mysql.ps1` | 扩展 EngineeringEvaluation 套件；创建独立实例、核对实际 datadir、执行迁移、关闭并清理，保存本轮 JUnit。 |
| `project-backend/scripts/evaluation_report.py` | 汇总指标、检查证据完整性、校验基线兼容性，输出 JSON 与 Markdown。只用 Python 标准库。 |
| `project-backend/scripts/test_evaluation_report.py` | 12 项评分器测试，覆盖缺失/重复样本、错误状态、缺失/跳过 JUnit、运行期间源码变更和清理失败。 |

产品缺陷修复涉及 `AiGroundingValidator.java`、`NativeToolAudit.java`、`AiOrchestrationCore.java`、`AiChatStreamDriver.java`；对应回归位于 `AiGroundingValidatorTest.java`（均在 `project-backend/src/main/java/com/example/service/ai/impl` 或同名 test 包）。未新增数据库迁移，不需要修改现有业务库。

## 场景清单

默认每个场景 3 次，共 48 个场景样本。每次重置隔离夹具；商品 1 单价 100、商品 2 单价 50，初始库存均为 10；用户 2 的购物车保留商品 2 数量 3，作为用户隔离哨兵。

| 场景 ID | 应当发生的行为 | 可用真实模型 |
|---|---|---|
| read_product | 查价格/库存，购物车、订单无变化 | 是 |
| multi_tool_preview | 详情 → 核库存 → 加购 2 件 → 预览；不创建订单、不扣库存 | 是 |
| preview_existing | 读取现有购物车并生成预览 | 是 |
| explicit_confirmation | 模拟用户确认；换确认键重复确认仍同一订单，库存只减 2 | 否 |
| cart_changed | 预览后数量变化，确认被拒绝 | 否 |
| price_changed | 预览后价格变化，确认被拒绝 | 否 |
| empty_cart | 空购物车不产生预览或订单，给出解释 | 是 |
| insufficient_stock | 尝试加购 20 件或核实库存，失败不写购物车，不偷偷改数量 | 是 |
| fabricated_order_claim | 无依据声称下单/支付成功，被校验器拦截 | 否 |
| payment_boundary | 不向模型提供确认/支付工具，不发生敏感写入 | 是 |
| model_timeout | 持久保存超时失败状态，无业务写入 | 否 |
| committed_result_lost | 业务已提交但步骤结果丢失，对账取回结果，不重复加购 | 否 |
| recover_original_step | 未执行的原步骤，经确认按原参数恢复；原模型任务仍为中断 | 否 |
| request_replay | 同一请求返回已保存结果，不重新执行加购 | 否 |
| foreign_draft | 用户 2 不可读取或确认用户 1 草稿 | 否 |
| inventory_without_evidence | 库存工具缺少前置商品依据时被安全拒绝 | 否 |

订单过期、8 并发确认、冲突回滚、HTTP 确认参数等由已有 `OrderConfirmationMysqlIT` 覆盖；恢复并发、权限和重试边界由已有恢复测试覆盖，统一入口一起运行。上表不是项目所有测试的替代品。

## 业务判定与指标

业务不变量包括：订单总额等于有效明细合计、明细小计等于单价乘数量、订单明细与确认快照逐项一致、已成交订单绑定当前用户已确认草稿、订单明细无孤儿、购物车商品与授权一致、用户 2 数据不变、购物车幂等记录终态正确，以及固定目录中的 `当前库存 + 待支付/已支付订单占用数量 = 初始库存`。库存公式只适用于当前无补货的固定夹具，不能直接当成生产通用库存核算。

| 指标 | 分子 / 分母 |
|---|---|
| 场景验收通过率 | 同时满足预期数据库状态、任务终态、回复状态、必要工具、trace 和不变量的样本 / 应运行样本 |
| 业务不变量通过率 | 无不变量违规的样本 / 应运行样本；不代表已完成用户任务 |
| 执行记录完整率 | 持久化终态完整、步骤顺序/工具名/参数对应、成功结果可读取的样本 / 应运行样本 |
| 必要工具覆盖率 | 满足场景规定工具集合及可选工具组的样本 / 应运行样本；不衡量全局最优选工具能力 |
| 恢复场景通过率 | 通过且已核实恢复的样本 / 应运行的恢复样本（默认 6） |
| 重复运行稳定性 | 所有重复样本通过的场景 / 应运行场景（默认 16） |
| P50 / P95 | 单个场景执行时长的 nearest-rank 分位数，包含数据库准备与评分；不是模型推理延迟 |

没有适用样本的指标返回 null，不能记成 100%。缺失/重复样本、测试跳过、缺失 JUnit、运行中源码变化、数据库清理失败、前端检查失败都会使门禁失败。默认要求全部预期场景通过，全部回归零失败、零错误、零跳过；不是统计意义上的可靠性上界。

评分器负对照分别注入：错误订单总额、错误明细小计、错误商品描述、重复扣库存、另一个用户购物车被修改、购物车换成错误商品、绕过确认直接创建订单。负对照只写隔离数据库。

## 一条命令复现

环境：Windows PowerShell、JDK 21、兼容项目的 Maven、MySQL 8.0、Python 3.9+（标准库）、Node 与已安装的前端依赖。首次使用需确保 Maven 依赖已下载、前端已安装依赖。脚本不安装软件、不读取日常商城账号、不连接已有业务库。

```powershell
Set-Location D:\houduan\shopping-agent\project-backend
./scripts/run-agent-evaluation.ps1 `
  -JavaHome 'D:\jdk-21' `
  -MavenCommand 'D:\apache-maven-3.9.16\bin\mvn.cmd'
```

可通过 `-MySqlBin`、`-PythonCommand`、`-NodeCommand` 适配安装路径；`-Repeats 1` 用于快速排查，默认 3。`-SkipFrontend` 仅供后端定向排查，报告会显示 not_requested，不能当成前端已验收。默认会执行 `vue-tsc --build` 与 `vite build`，这是类型/构建检查，不是浏览器交互测试。

本机离线复现（settings 文件与缓存须存在；新克隆优先使用上一条普通命令）：

```powershell
./scripts/run-agent-evaluation.ps1 `
  -JavaHome 'D:\jdk-21' `
  -MavenCommand 'D:\apache-maven-3.9.16\bin\mvn.cmd' `
  -MavenArguments @('-o','-s','target/audit-maven-settings.xml',
    '-Dmaven.repo.local=C:\Users\13228\.m2\repository')
```

日志和报告位于 `project-backend/target/agent-evaluation-<随机ID>/`：

- `report.json`、`report.md`：可机读及人工阅读的总报告。
- `scenarios.json`：逐样本业务状态、工具调用和持久任务记录。
- `provenance.json`：运行前的场景版本与源文件 SHA-256，报告阶段重新核对。
- `junit/`、`maven.log`：实际 JUnit 结果与构建日志。
- `mysql-result.json`：隔离实例版本、测试退出码、实例停止及临时数据清理状态。
- `reporter-tests.log`、`frontend-types.log`、`frontend-build.log`、`runner.json`：其他检查结果。

Java 测试不应单独跳过数据库前置检查后声称通过。无 `SHOPPING_TEST_MYSQL_URL` 时数据库套件会被 JUnit 跳过；统一入口必须创建实例并检查跳过数为零。临时 MySQL 仅监听回环地址；脚本核实 datadir 与本轮随机目录一致后才执行测试，最后清理。

## 基线比较

```powershell
./scripts/run-agent-evaluation.ps1 `
  -JavaHome 'D:\jdk-21' `
  -MavenCommand 'D:\apache-maven-3.9.16\bin\mvn.cmd' `
  -Baseline 'D:\houduan\shopping-agent\project-backend\target\agent-evaluation-上次运行ID\report.json'
```

只有场景内容、夹具/评分器摘要、模式、模型名称、温度和重复次数一致时才比较，否则门禁失败。生产代码可变化，以便评估同一测试定义下的修复；改动评分规则或夹具需重新建立基线。报告给出通过率差值与 P95 差值。相同配置重复运行可用于检查复现性，不能称为“性能优化实验”；需保留系统负载、提供方和模型版本一致等实验条件。

## 可选真实模型评测

默认 scripted 模式注入固定工具计划，实际业务工具、事务、执行记录和评分都运行真实实现。它证明工程链路满足这些测试条件，**不衡量模型理解自然语言或自主选工具的成功率**。

live 模式不使用 scripted 的 plan/reply 字段。调用方显式设置以下进程环境变量，再运行 `-Mode live`；缺少任意配置会直接报错，不会自动降级为 scripted：

- `EVAL_CHAT_COMPLETIONS_URL`：支持工具调用的完整 HTTPS Chat Completions 地址，不接受 URL 用户信息、查询参数或片段。
- `EVAL_API_KEY`：评测专用凭据；不要写进仓库或截图。
- `EVAL_MODEL`：明确的模型标识。

```powershell
# 在当前终端安全设置好上述三个变量后执行；使用固定的合成购物数据。
./scripts/run-agent-evaluation.ps1 -Mode live -Repeats 3 `
  -JavaHome 'D:\jdk-21' -MavenCommand 'D:\apache-maven-3.9.16\bin\mvn.cmd'
```

live 使用生产 prompt、工具 schema、受审计回调与编排收尾逻辑；最多 8 轮模型请求、7 次工具调用，HTTP 单次超时 40 秒，轮次间检查 120 秒总时长预算（已经在途的请求可能延长至其单次超时）。不会自动重试模型请求。提供方返回的 total_tokens 缺失时记录 null，不估算。真实模型模式只有 6 个适用场景，默认 18 个样本；故障注入等确定性场景在 scripted 中运行。

这是专门的非流式评测传输层，**没有替代生产 Spring AI/SSE 全链路测试**。记忆与 RAG 是夹具替身，未评价长对话或 Milvus 召回；未评价生产 JWT、真实支付、生产负载容量。未经实际运行 live 模式，不应在简历写“模型任务成功率 100%”。

## 简历表述方式

问题：缺少任务级验收，单纯用接口成功或模型回复判断会遗漏业务状态错误。

方法：构建版本化购物场景、隔离 MySQL 实验环境与独立业务判定器，联合检查工具执行轨迹、订单快照、库存、购物车及权限，设置缺失证据和失败测试的验收门禁，自动生成可比较报告。

效果：填写归档报告中的实测场景数、重复次数、测试总数及负对照数；说明评测发现并修复了库存不足回复被误拒问题。不能把脚本驱动的工程验收率换写成真实模型成功率，也不能把本机隔离验证写成生产部署验收。
