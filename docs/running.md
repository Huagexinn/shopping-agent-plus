# 本地运行与复现

项目有两条运行路径：先运行不依赖模型的工程验收，检查三项改造；再启动完整商城体验 AI 页面。两者的依赖和验证范围不同。

## 1. 运行工程验收

准备 Windows PowerShell、JDK 21、Maven 3.9+、MySQL 8.0 的本地程序、Python 3.9+、Node 24 和 pnpm。MySQL 测试脚本会启动自己的临时实例，不使用正在运行的商城数据库。首次运行 Maven 需能下载依赖。

在仓库根目录执行：

```powershell
Set-Location project-frontend
pnpm install --frozen-lockfile
Set-Location ../project-backend
./scripts/run-agent-evaluation.ps1 -JavaHome '<JDK 21 安装目录>'
```

将尖括号占位符替换成实际路径。如果环境变量 JAVA_HOME 已正确配置为 JDK 21，可省略 `-JavaHome`。命令应在后端目录运行。

工具不在默认路径时：

```powershell
./scripts/run-agent-evaluation.ps1 `
  -JavaHome '<JDK 21 安装目录>' `
  -MavenCommand '<Maven 安装目录>\bin\mvn.cmd' `
  -MySqlBin '<MySQL 安装目录>\bin' `
  -PythonCommand '<Python 安装目录>\python.exe' `
  -NodeCommand '<Node 安装目录>\node.exe'
```

脚本依次执行报告器测试、前端类型检查及构建、隔离 MySQL 迁移与后端测试，最后汇总报告。默认 16 个场景各运行 3 次；非零退出码表示验收未通过。报告目录为 `project-backend/target/agent-evaluation-<运行ID>/`，首先阅读 `report.md`，排查时看 `report.json`、`maven.log` 与 `junit/`。

该路径不需要启动 Redis、Milvus 或配置模型 Key；检索与会话记忆使用测试替身。不能将通过结果解释为真实模型或生产 SSE 全链路验收。指标和可选 live 评测见 [工程评价说明](agent-engineering-evaluation.md)。

## 2. 启动完整商城

### 服务与端口

| 组件 | 当前配置 | 说明 |
|---|---|---|
| 后端 | 8090 | 来自 application.yml |
| 前端 | Vite 启动输出的地址，通常 5173 | 端口占用时以终端输出为准 |
| MySQL | 默认 3306 | DB_URL 可覆盖 |
| Redis | dev 配置为 6380 | 如果实际为 6379，覆盖 spring.data.redis.port |
| Milvus | 默认 19530 | 需要匹配账户、集合和 1024 维 embedding |

使用可用的本地 MySQL、Redis、Milvus。仓库中的 `milvus/docker-compose.yml` 是本地开发配置，不是生产部署方案；使用它之前应核对自己的镜像、端口与认证设置。本轮文档整理没有重新验证容器部署。

### 后端配置

先创建独立开发数据库，再配置 [application.yml](../project-backend/src/main/resources/application.yml) 所引用的环境变量：

| 变量 | 用途 |
|---|---|
| DB_URL | 开发数据库 JDBC 地址；默认 shopping_mall |
| DB_USERNAME / DB_PASSWORD | MySQL 用户名和密码 |
| JWT_SECRET | 本地生成的随机签名密钥 |
| DEEPSEEK_API_KEY | 对话模型凭据 |
| QWEN_EMBEDDING_API_KEY | embedding 凭据 |
| MILVUS_HOST / MILVUS_PORT | 向量数据库地址 |
| MILVUS_USERNAME / MILVUS_PASSWORD | 与实际服务匹配的账户设置 |

[后端变量清单](../project-backend/.env.example)只提供占位符。**Spring Boot 不会自动读取本项目的 `.env` 文件**：请在 IDEA 的运行配置中设置这些环境变量，或者在启动进程前使用 PowerShell 的 `$env:变量名 = '实际值'` 设置。不要把真实凭据写进示例文件。

模型和 embedding 地址、模型名称需按你实际开通的服务调整；现有 embedding 地址包含特定服务实例，不能假设换一份 Key 就能使用。可通过 Spring 的外部配置覆盖对应属性，例如 `spring.ai.openai.embedding.base-url`。本地覆盖文件使用被 Git 忽略的 `application-local.yml`，并在启动时明确加载相应 profile；不要修改已归档实验以伪装新环境已验证。

从后端目录启动：

```powershell
mvn spring-boot:run
```

如使用 local profile 且仍需 dev 的 Redis 配置：

```powershell
mvn spring-boot:run '-Dspring-boot.run.profiles=dev,local'
```

新库启动时执行 Flyway V1–V11。已有库的升级要求见 [数据库迁移](database-migrations.md)。迁移创建表结构，不会提供真实商品数据；演示时应在独立开发库录入合成商品，测试夹具中的两商品数据可供参考。不要上传或导入带用户信息的业务备份作为公开种子数据。

### 前端配置与启动

另开一个终端，在前端目录操作。首次配置时执行复制；已有 `.env` 时直接核对其中的代理地址，避免覆盖个人配置。

```powershell
Copy-Item .env.example .env
pnpm install --frozen-lockfile
pnpm dev
```

示例中的 `VITE_API_PROXY_TARGET=http://localhost:8090` 已与当前后端一致。`.env` 只放浏览器/代理相关配置，不能放数据库密码或模型 Key。修改代理后重启前端开发服务。

## 3. 手工演示三项改造

1. 注册登录，在合成商品上加购；打开 AI 页面提交请求，观察工具步骤与任务记录。
2. 发送“根据当前购物车生成订单预览，先不要下单”，确认只有预览，没有订单和库存扣减。
3. 从 AI 预览卡跳转，核对原预览并显式确认；检查待支付订单、库存和购物车。刷新原预览，确认返回同一订单。
4. 在独立测试环境使用恢复测试夹具演示断线/响应丢失，观察业务结果对账与原步骤恢复；不要靠盲目重复点击制造故障。
5. 运行工程验收脚本，阅读逐样本预期、实际业务状态和失败原因。

已有浏览器夹具的操作与边界保存在 [历史验收记录](history/implementation-notes.md)，它们需人工驱动，不属于默认 156 项自动化测试。

## 常见问题

- **pnpm 无法识别**：先在自己的 Node 环境中安装/启用 pnpm，再打开新终端确认 `pnpm --version`；不要默认 IDE 与其他终端共享相同 PATH。
- **AI 页面没有反应**：先检查浏览器缩放、底部输入框、登录状态及开发者工具的网络请求，再核对后端、模型和检索服务日志。
- **前端请求失败**：核对代理是否指向后端 8090，而非原来的 8080。
- **数据库迁移失败**：保留错误和迁移历史，按迁移指南核对；不要删除表或跳过失败版本。
- **测试显示 skipped**：不要把直接运行未配置数据库的 IT 套件当作通过，使用隔离脚本。

本次文档整理依据现有配置与已归档测试补全说明；没有重新安装依赖或从零部署所有中间件。
