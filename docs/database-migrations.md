# 数据库迁移

所有迁移保留在唯一目录 [db/migration](../project-backend/src/main/resources/db/migration)，不另外复制成一套初始化 SQL，避免副本与 Flyway 历史分叉。

| 版本 | 归属 | 用途 |
|---|---|---|
| V1–V7 | 基础项目的商城迁移 | 用户、商品、购物车、订单、商品规格与支付生命周期 |
| [V8](../project-backend/src/main/resources/db/migration/V8__create_cart_operation_records.sql) | 改造一 | 购物车操作幂等记录；用户与操作标识唯一、参数指纹、结果 |
| [V9](../project-backend/src/main/resources/db/migration/V9__create_agent_runs.sql) | 改造一 | Agent 任务与工具步骤持久化 |
| [V10](../project-backend/src/main/resources/db/migration/V10__create_agent_step_recovery.sql) | 改造一 | 结果来源、恢复次数与恢复尝试记录 |
| [V11](../project-backend/src/main/resources/db/migration/V11__persist_order_confirmation.sql) | 改造二 | 持久预览、确认键、快照指纹、订单关联与确认状态约束 |
| 无新增迁移 | 改造三 | 在临时实例应用 V1–V11，验证业务与工程行为 |

## 新环境

创建独立空数据库，配置连接后启动应用，由 Flyway 按版本执行 V1–V11。迁移不等于业务种子数据：自动评测会自己生成合成商品与用户，不要把私有商城数据库备份作为公开仓库的初始化文件。

## 已有数据库

先备份并核对 `flyway_schema_history` 和实际表结构，再按当前应用版本执行缺失迁移。配置中的 baseline-on-migrate 不意味着任意已有表结构与迁移兼容。已存在手工表、失败迁移或 checksum 不一致时，应针对差异制定修复方案，不能直接删除业务表、编辑已成功迁移文件或把 success 强改为 1 作为通用安装步骤。

V11 替代原 Redis 订单草稿存储；旧 Redis 草稿不会自动转换，旧预览需要重新生成。前后端需一起更新，确认请求需要 `confirmed: true`、原 `fingerprint` 与 `Idempotency-Key`。

本地历史上对 V8 的专项修复只适用于当时已备份、逐项核对过的数据库；不把那次手工处理传播成读者可直接复制的迁移命令。

## 可复现验证

从后端目录运行 `./scripts/run-agent-evaluation.ps1`；脚本创建随机端口的独立 MySQL，核实实际 datadir，执行测试后清理。当前归档实验的 V1–V11 已在 MySQL 8.0.46 上运行成功，见 [实验结果](experiments.md)。不要将手动设置的业务数据库地址传给测试类代替隔离脚本。
