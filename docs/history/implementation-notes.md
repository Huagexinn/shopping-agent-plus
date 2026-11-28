> 历史归档：这是本 Fork 整理前的说明与阶段改造记录，不是上游 README 的原样副本。阶段部署状态、端口及运行建议可能过时；当前说明以 [项目首页](../../README.md) 和 [运行指南](../running.md) 为准。

# 数码商城AI购物助手

一个前后端分离的**数码商城 + AI 购物助手**项目。后端提供商品、购物车、订单等标准电商能力，并在其上叠加一层由大模型驱动的导购助手：支持流式对话、Tool Calling（工具调用）与混合 RAG（向量检索 + 数据库实时回查），让用户可以直接用自然语言完成「找商品 → 看库存 → 加购物车 → 生成订单预览」的完整链路。

项目同时作为 **Spring Boot 4 + Spring AI 2.0** 的工程化实践样板，重点演示在真实业务里如何约束 AI 的边界：AI 只能做**只读查询**和**购物车、订单预览**这类可回滚的写操作，**下单确认与支付始终由用户在前端显式触发**。

---

## 主要功能

### 商城业务

| 模块 | 功能 | 说明 |
| --- | --- | --- |
| 用户认证 | 注册 / 登录 / 查询当前用户 | 密码 BCrypt 加密存储，登录签发 JWT，全站无状态认证 |
| 商品 | 分页列表、详情查询 | 支持品牌、价格区间、关键词等条件筛选，仅返回上架商品 |
| 购物车 | 增 / 删 / 改 / 查 / 清空 | 按用户隔离，商品维度唯一约束，含数量上限校验 |
| 订单 | 订单预览 → 确认下单 → 支付 / 取消 | 预览不扣库存；确认后扣减库存；支付超时自动关单并归还库存 |

### AI 购物助手

| 能力 | 说明 |
| --- | --- |
| 流式对话 | 基于 SSE（`text/event-stream`）逐字返回，含文本增量、工具调用、引用来源、错误等事件类型 |
| Tool Calling | 单次请求动态注册 6 个原生工具，模型自主决定调用时机 |
| 混合 RAG | Milvus 负责语义召回，召回结果再由 MySQL 实时回查价格、库存、上架状态，避免「向量库数据过期」导致的错误报价 |
| 会话记忆 | 基于 Redis 按用户隔离的多轮上下文，滑动窗口 + TTL 过期，Redis 不可用时自动降级为无历史模式 |
| 事实校验 | 模型输出经过二次校验，未命中的商品 / 库存信息会被拦截或修正，降低幻觉 |
| 调用审计 | 每次工具调用落审计记录，校验商品 ID 是否在本次检索结果白名单内 |
| 幂等保护 | 购物车写操作按「操作指纹」幂等，模型重复调用不会造成重复加购 |

### AI 可用工具清单

| 工具 | 类型 | 用途 |
| --- | --- | --- |
| `HybridRagTool` | 只读 | 检索商品与知识资料，返回经 MySQL 回查的商品事实 |
| `ProductDetailTool` | 只读 | 查询单个在售商品的最新详情、价格、规格、库存 |
| `InventoryTool` | 只读 | 查询商品当前可用库存 |
| `OrderQueryTool` | 只读 | 查询当前用户自己的订单列表 / 订单详情 |
| `CartTool` | 可写 | 查看或修改当前登录用户的购物车（仅 `get` / `add` / `update` / `remove`） |
| `OrderPreviewTool` | 可写 | 基于购物车生成**不扣库存**的订单预览，不能确认订单 |

---

## 技术栈

### 后端

| 分类 | 技术 | 版本 |
| --- | --- | --- |
| 语言 / 运行时 | Java | 21 |
| 框架 | Spring Boot | 4.1.1 |
| AI 框架 | Spring AI（OpenAI 兼容接入） | 2.0.1 |
| ORM | MyBatis-Plus（含 jsqlparser） | 3.5.17 |
| 数据库 | MySQL | 8.x |
| 数据库版本管理 | Flyway | 随 Boot 版本 |
| 缓存 / 会话记忆 | Redis | 6.x+ |
| 向量数据库 | Milvus | 2.x |
| 安全 | Spring Security + JJWT | 0.12.6 |
| 接口文档 | springdoc-openapi（Swagger UI） | 2.8.13 |
| 其他 | Lombok、commons-lang3、spring-boot-starter-validation | — |

### 前端

| 分类 | 技术 | 版本 |
| --- | --- | --- |
| 框架 | Vue | 3.5 |
| 语言 | TypeScript | 6.x |
| 构建工具 | Vite | 8.x |
| 状态管理 | Pinia | 3.x |
| 路由 | Vue Router | 4.x |
| UI 组件库 | Ant Design Vue（+ @ant-design/icons-vue） | 4.2 |
| 请求 | Axios | 1.x |
| 内容渲染 | marked + DOMPurify | — |
| 包管理器 | pnpm | — |
| Node | >= 24 < 25 | — |

### AI 服务依赖

| 用途 | 服务 | 默认配置 |
| --- | --- | --- |
| 对话模型 | DeepSeek（OpenAI 兼容协议） | `base-url: https://api.deepseek.com`，`model: deepseek-v4-flash` |
| 向量化模型 | 通义千问 Embedding（OpenAI 兼容协议） | 1024 维，`model: qwen3.7-text-embedding` |
| 向量存储 | Milvus Collection：`phone_knowledge_1024` | 距离度量 COSINE |

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                     浏览器（Vue 3 SPA）                       │
│  HomePage · Login/Register · CartPage · OrdersPage ·         │
│  AiAssistantPage（SSE 流式对话）                              │
└───────────────────────────┬──────────────────────────────────┘
                            │ /api/**（Vite dev/preview 代理）
┌───────────────────────────▼──────────────────────────────────┐
│                    Spring Boot 4 应用                         │
│                                                              │
│  ┌───────────── 过滤器 / 安全层 ─────────────┐                │
│  │ RequestTraceFilter → JwtAuthenticationFilter│              │
│  │ SecurityConfig（无状态 JWT + 接口级授权）    │              │
│  └────────────────────┬─────────────────────┘                │
│                       │                                      │
│  ┌────────────────────▼────────── Controller 层 ───────────┐ │
│  │ AuthController · ProductController · CartController      │ │
│  │ OrderController · AiController                           │ │
│  └────────────────────┬─────────────────────────────────────┘ │
│                       │                                      │
│  ┌────────────────────▼────────── Service 层 ───────────────┐ │
│  │ 商城域：Auth / Product / Cart / Order                    │ │
│  │   · OrderDraftService   持久化预览（MySQL，有效期 10m）  │ │
│  │   · OrderCloseScheduler 支付超时扫描关单                  │ │
│  │   · 幂等服务（购物车操作 / 订单）                          │ │
│  ├──────────────────────────────────────────────────────────┤ │
│  │ AI 域：AiOrchestrationCore（同步/流式共用编排核心）        │ │
│  │   AiPromptBuilder → 身份边界 + 系统提示词                 │ │
│  │   NativeToolSet   → 单请求级 6 个原生 Tool                │ │
│  │   AuditedToolCallback → 工具调用审计                      │ │
│  │   AiGroundingValidator / AiStreamingGroundingGuard        │ │
│  │        → 同步与流式两条链路共用同一套事实校验              │ │
│  │   AiResponseAssembler → 组装响应 + 写入会话记忆            │ │
│  └────────────────────┬─────────────────────────────────────┘ │
└───────────────────────┼──────────────────────────────────────┘
        ┌───────────────┼────────────────┬──────────────┐
        ▼               ▼                ▼              ▼
   ┌─────────┐   ┌────────────┐   ┌──────────┐   ┌─────────────┐
   │  MySQL  │   │   Redis    │   │  Milvus  │   │ LLM 服务    │
   │ 业务数据 │   │ 会话记忆    │   │ 语义召回  │   │ DeepSeek    │
   │ Flyway  │   │ 草稿/幂等   │   │ 1024 维  │   │ + Embedding │
   └─────────┘   └────────────┘   └──────────┘   └─────────────┘
```

**两个关键设计点：**

1. **同步与流式共用一条编排链路** —— 提示词构建、Tool 注册、身份边界、事实校验都收敛在 `AiOrchestrationCore` 中，同步走 `.call()`、流式走 `.stream()`，流式接口无法绕过事实校验另起炉灶。
2. **身份只来自服务端上下文** —— 工具的 `userId` 一律从 `ToolContext` 读取并与当前登录用户比对，模型既拿不到、也无法伪造用户身份；结合检索白名单，从结构上堵死越权查询。

---

## 项目结构

```
shopping-agent-ai/
├── README.md
├── project-backend/                     # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/
│       │   ├── ShoppingAgentApplication.java
│       │   ├── ai/                      # AI 能力层
│       │   │   ├── config/              # ChatClient 装配
│       │   │   ├── memory/              # Redis 会话记忆（接口 + 实现）
│       │   │   ├── model/               # Tool 请求 / 响应模型
│       │   │   ├── rag/                 # 混合 RAG、知识入库
│       │   │   └── tool/                # 4 个原生 Tool + 只读策略
│       │   ├── common/                  # 统一响应、请求上下文常量
│       │   ├── config/                  # Security / MyBatis-Plus / OpenAPI / 定时任务
│       │   ├── controller/              # Auth / Product / Cart / Order / Ai
│       │   ├── dto/                     # 分域 Request / Response
│       │   ├── entity/                  # 实体 + OrderStatus 枚举
│       │   ├── exception/               # 业务异常 + 全局异常处理
│       │   ├── filter/                  # 请求链路追踪
│       │   ├── mapper/                  # MyBatis-Plus Mapper
│       │   ├── security/                # JWT 签发 / 校验 / 认证失败处理
│       │   ├── service/                 # auth / product / cart / order / ai
│       │   └── utils/                   # 共享常量
│       └── resources/
│           ├── application.yml          # 数据源、Flyway、AI、JWT、订单策略
│           ├── application-dev.yml      # dev profile
│           ├── db/migration/            # Flyway V1 ~ V7
│           └── rag/                     # 知识库文档（6 篇 txt）
│
└── project-frontend/                    # Vue 3 前端
    ├── package.json
    ├── vite.config.ts                   # 别名 @、/api 代理
    ├── .env.example                     # 环境变量模板
    ├── index.html
    └── src/
        ├── api/                         # axios 实例 + 各业务域接口
        ├── assets/                      # 全局样式
        ├── layouts/                     # AppLayout 应用骨架
        ├── pages/                       # 首页 / 登录 / 注册 / 购物车 / 订单 / AI 助手
        ├── router/                      # 路由表
        ├── stores/                      # Pinia 状态
        ├── types/                       # TS 类型定义
        └── utils/                       # markdown 渲染、token 存储
```

---

## 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | 21（后端编译目标为 Java 21） |
| Maven | 3.9+ |
| MySQL | 8.x，需提前创建数据库 `shopping_mall` |
| Redis | 6.x+，默认 `localhost:6379`，无密码 |
| Milvus | 2.x，默认 `localhost:19530`（可用 Docker 或 Milvus Lite 启动） |
| Node.js | `>= 24 < 25` |
| pnpm | 9+（项目使用 `pnpm-lock.yaml`） |
| 网络 | 需能访问 DeepSeek 与通义千问 Embedding 的接口地址 |
| API Key | `DEEPSEEK_API_KEY`、`QWEN_EMBEDDING_API_KEY` 各一份 |

端口占用：后端 `8080`、前端 `5173`、MySQL `3306`、Redis `6379`、Milvus `19530`。

---

## 环境配置

### 1. 后端配置（`project-backend/src/main/resources/application.yml`）

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/shopping_mall
    username: root
    password: 123456          # ← 改成自己的密码
  flyway:
    enabled: true             # 启动时自动执行 db/migration 下的迁移脚本
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:}
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-v4-flash
      embedding:
        api-key: ${QWEN_EMBEDDING_API_KEY:}
        base-url: https://ws-xppsn0j6du7g1eu4.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
        model: qwen3.7-text-embedding
    vectorstore:
      milvus:
        client:
          host: localhost
          port: 19530
        collection-name: phone_knowledge_1024
        embedding-dimension: 1024
        metric-type: COSINE
  security:
    jwt:
      key: 'abcdefghijklmn'   # ← 生产环境务必替换为强随机密钥（至少 32 字符）
      expire: 72              # Token 有效期（小时）

app:
  order:
    payment-timeout: 30m      # 支付超时时间，超时自动关单
    close-scan-delay: 1m      # 关单扫描间隔
    draft-ttl: 10m            # 订单预览草稿有效期
```

### 2. 环境变量

两个 API Key 通过环境变量注入，不要写进配置文件：

```bash
export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
export QWEN_EMBEDDING_API_KEY="你的通义千问 Embedding API Key"
```

### 3. 前端配置（`project-frontend/.env`）

复制 `.env.example` 为 `.env`，按需修改：

```bash
VITE_API_BASE_URL=/api                      # 浏览器侧请求前缀，配合代理保持 /api
VITE_API_PROXY_TARGET=http://localhost:8080 # Vite dev/preview 的代理目标
```

---

## 快速启动

### 1. 启动依赖中间件

```bash
# MySQL：创建数据库（表结构由 Flyway 自动迁移，无需手工建表）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS shopping_mall DEFAULT CHARSET utf8mb4;"

# Redis
redis-server

# Milvus（Docker 方式示例，端口需为 19530）
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 milvusdb/milvus:latest
```

### 2. 启动后端

```bash
cd project-backend

# 注入 API Key
export DEEPSEEK_API_KEY="..."
export QWEN_EMBEDDING_API_KEY="..."

mvn spring-boot:run
```

启动时 Flyway 会自动执行 `V1` ~ `V7` 迁移脚本建表；知识入库服务会读取 `resources/rag/*.txt` 并写入 Milvus（首次启动耗时较长）。

验证：
- 接口文档：<http://localhost:8080/swagger-ui.html>
- 商品列表：<http://localhost:8080/api/products>

### 3. 启动前端

```bash
cd project-frontend
pnpm install
pnpm dev
```

打开 <http://localhost:5173>，先注册 / 登录，即可浏览商品、加购物车、下单，并在「AI 助手」页体验流式导购对话。

---

## 生产构建

### 后端

```bash
cd project-backend
mvn clean package -DskipTests
java -jar target/shopping-agent-ai-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

推荐通过环境变量覆盖敏感配置，而不是改 jar 内的 `application.yml`：

```bash
export SPRING_DATASOURCE_URL="jdbc:mysql://<host>:3306/shopping_mall"
export SPRING_DATASOURCE_USERNAME="<user>"
export SPRING_DATASOURCE_PASSWORD="<password>"
export SPRING_AI_OPENAI_API_KEY="$DEEPSEEK_API_KEY"
export SPRING_AI_VECTORSTORE_MILVUS_CLIENT_HOST="<milvus-host>"
```

### 前端

```bash
cd project-frontend
pnpm build          # 等价于 vue-tsc 类型检查 + vite build
pnpm preview        # 本地预览构建产物
```

产物在 `project-frontend/dist/`。生产环境建议：

- 用 Nginx 托管 `dist/` 静态文件，并开启 SPA fallback（`try_files $uri $uri/ /index.html;`）。
- 将 `/api` 反向代理到后端 `8080`，SSE 流式接口需关闭代理缓冲：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_buffering off;          # 关键：否则流式输出会被攒批
    proxy_read_timeout 300s;
}
```

---

## 注意事项

**安全相关**

1. **务必更换 JWT 密钥** —— `spring.security.jwt.key` 默认值仅为占位，上线前必须替换为强随机密钥，否则 Token 可被伪造。
2. **不要把 API Key 提交到仓库** —— 仓库根 `.gitignore` 已忽略 `.env` / `.env.*`，密钥请统一走环境变量注入。
3. **数据库账号密码不要沿用默认值** —— `root/123456` 仅用于本地开发。
4. **AI 不能直接成交** —— 设计上 AI 只具备只读查询 + 购物车修改 + 订单预览能力，**确认下单和支付必须由用户在前端显式操作**，避免模型幻觉造成真实扣款。
5. **工具身份校验不可绕过** —— 用户 ID 一律从服务端 `ToolContext` 读取，模型传入的任何用户标识都不会被采信。

**运行与依赖**

6. **首次启动较慢属正常** —— Milvus 知识入库、Flyway 迁移都在启动阶段执行；知识文档是 6 个 txt 文件（`resources/rag/`），修改后重启即可重新入库。
7. **RAG 有降级行为** —— Milvus 不可用时检索会抛 `RagUnavailableException` 并由上层兜底，AI 可能暂时无法回答商品相关问题，但不影响商城业务接口。
8. **Redis 不可用时自动降级** —— 会话记忆可能退化为无历史上下文；购物车操作幂等、Agent 任务记录、订单预览和确认结果以 MySQL 为依据，不依赖 Redis 缓存命中。
9. **价格与库存以 MySQL 为准** —— Milvus 只负责语义召回，任何价格 / 库存结论都会实时回查数据库，排查「报价不一致」问题时优先看数据库。
10. **接口权限分两类** —— `/api/auth/register`、`/api/auth/login`、`/api/products/**`、Swagger 相关路径免认证；`/api/auth/me`、`/api/cart/**`、`/api/orders/**`、`/api/ai/**` 均需携带 `Authorization: Bearer <token>`。

**前端与部署**

11. **Node 版本有硬约束** —— `package.json` 限定 `>= 24 < 25`，版本不符可能触发依赖引擎报错。
12. **统一用 pnpm** —— 项目锁文件为 `pnpm-lock.yaml`，混用 npm / yarn 易造成依赖树不一致。
13. **流式接口需关闭代理缓冲** —— SSE（`/api/ai/chat/stream`）在 Nginx 等代理后必须设置 `proxy_buffering off`，否则前端会「一次性输出」而不是逐字返回。
14. **订单相关时限可调** —— 支付超时 30 分钟、草稿有效期 10 分钟，可通过 `app.order.*` 调整；关单扫描间隔越短，超时订单归还库存越及时。

---

## 接口速览

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录，返回 JWT | 否 |
| GET | `/api/auth/me` | 查询当前用户信息 | 是 |
| GET | `/api/products` | 商品分页列表 | 否 |
| GET | `/api/products/{id}` | 商品详情 | 否 |
| GET | `/api/cart` | 查询购物车 | 是 |
| POST | `/api/cart/items` | 添加购物车项 | 是 |
| PUT | `/api/cart/items/{productId}` | 修改购物车项数量 | 是 |
| DELETE | `/api/cart/items/{productId}` | 移除购物车项 | 是 |
| DELETE | `/api/cart` | 清空购物车 | 是 |
| POST | `/api/orders/preview` | 生成订单预览（不扣库存） | 是 |
| GET | `/api/orders/drafts/{draftId}` | 读取本人原预览、可用性和已确认订单 ID | 是 |
| POST | `/api/orders/{draftId}/confirm` | 确认预览草稿并下单 | 是 |
| POST | `/api/orders` | 直接下单 | 是 |
| GET | `/api/orders` | 订单分页列表 | 是 |
| GET | `/api/orders/{id}` | 订单详情 | 是 |
| POST | `/api/orders/{id}/pay` | 支付订单 | 是 |
| POST | `/api/orders/{id}/cancel` | 取消订单 | 是 |
| POST | `/api/ai/chat` | AI 对话（同步返回） | 是 |
| POST | `/api/ai/chat/stream` | AI 对话（SSE 流式返回） | 是 |
| GET | `/api/ai/runs/{clientRequestId}` | 本人任务状态、工具步骤和保存的回复 | 是 |
| GET | `/api/ai/runs?conversationId=...` | 本人当前会话最近 20 次任务 | 是 |
| POST | `/api/ai/runs/{clientRequestId}/steps/{sequence}/recover` | 核对后按原参数恢复一个步骤；购物车写入需 `confirmed: true` | 是 |

## 改造记录：聊天请求跨重试的购物车幂等

**问题**：原生工具每次执行都生成随机操作标识，SSE 写操作成功但响应丢失后，前端同步重发会被当成另一笔操作。原操作标识还包含数量，重试改数量也会绕过去重。

**方法**：聊天请求必须携带 `clientRequestId`（8～64 个字母、数字、下划线或短横线，首字符为字母或数字）。前端每次主动提交生成一次 UUID，自动重试复用原 payload。购物车写操作标识为 `ai-cart-v1:<clientRequestId>:<operation>:<productId>`；用户身份由服务端注入，并以 `(user_id, operation_id)` 唯一约束隔离。数量仍参与参数指纹，相同操作标识下改数量会报业务冲突。Redis 只缓存已提交结果，操作记录和业务修改在同一个 MySQL 事务中提交。

一个请求中同一商品的同类操作只能有一个成功参数版本；主动追加或修改应发送新请求。这一层只保证相同写操作的重试幂等，单靠该层不能阻止模型在重试中改选商品或操作。下节进一步加入任务入口去重，阻止同一请求重新调用模型；仍不能据此宣称整条 Agent 任务恰好执行一次。

**迁移**：新增 V8 创建 `cart_operation_records`。如果已有手工创建的同名表，V8 会明确失败，应先核对表结构和唯一约束再纳入迁移，不要删除业务表或直接跳过校验。测试只使用独立临时数据库，不修改现有商城数据。

**确定性回归结果（2026-11-26，内存业务与幂等替身）**：新增 8 个编排测试，修改前 4 个失败、修改后 8 个全部通过。一次丢失响应后的重发：实际写入从 2 次变为 1 次；8 个并发重试：实际写入从 8 次变为 1 次；改数量被拒绝，新请求和不同用户仍可正常执行。包括已有测试共运行 29 个用例，全部通过。这些结果验证真实工具回调的标识传递，不替代真实 MySQL 的并发与事务验证，也不是线上成功率。

在 JDK 21 和 Maven 环境中，从后端目录运行：

```powershell
mvn "-Dtest=CartRequestRetryTest,AiRequestIdentityTest,AiGroundingValidatorTest,NativeToolPolicyTest,CartToolGovernanceTest" test
```

另有 `CartOperationMysqlIT` 验证真实 MySQL 唯一约束、并发、业务/系统失败回滚、Redis 缺失时重放和用户隔离。该测试仅在显式提供测试连接时启用，创建随机 `shopping_agent_test_*` 数据库并在结束时删除自己创建的库。账号需要创建/删除临时数据库的权限，不需要模型、Redis 或 Milvus：

```powershell
$env:SHOPPING_TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/'
$env:SHOPPING_TEST_MYSQL_USER = '<测试账号>'
$env:SHOPPING_TEST_MYSQL_PASSWORD = '<测试密码>'
mvn "-Dtest=CartOperationMysqlIT" test
```

**真实数据库验证（2026-11-26）**：使用本机 MySQL 8.0.46 启动独立临时实例，6 个集成用例全部通过，无失败、错误或跳过。V8 已在临时数据库执行；验证使用真实 MyBatis Mapper、事务管理器和幂等服务，以测试计数表承载业务写入，覆盖 8 并发去重、成功结果重放、指纹冲突、业务失败回滚后重试、系统失败回滚、用户隔离及操作标识大小写区分。测试结束确认临时库已删除、临时 MySQL 已关闭且数据目录已清理。该结果不代表完整 CartService、订单或 Agent 流程已经通过集成验收，V8 仍未应用到现有业务数据库。

运行结果归档在 [MySQL 验证报告](../../project-backend/verification/cart-mysql-2026-11-26.json)。Windows 本机已安装 MySQL 8.0 时，可通过以下脚本重复验证，无需提供现有数据库账号。脚本仅监听回环地址，使用随机端口和临时数据目录，并保留日志与清理结果；如果执行环境限制本机网络，需要允许该脚本访问本机临时实例。

```powershell
# 在 project-backend 目录运行；Maven/JDK 路径可通过参数覆盖。
.\scripts\test-cart-mysql.ps1 -JavaHome '<JDK 21 目录>'
```

## 改造记录：Agent 任务落库与断线结果查询

**问题**：原执行审计仅保留在内存和日志中，断线后用户无法确认哪些工具已执行。即使购物车单操作幂等，同一请求再次进入模型仍可能重新规划；工具异常还被误述为“未产生副作用”。

**方法与代码位置**：

| 位置 | 改动及原因 |
| --- | --- |
| `project-backend/src/main/resources/db/migration/V9__create_agent_runs.sql` | 新增 `agent_runs`、`agent_run_steps`，保存任务状态、回复、工具入参和结果；以 `(user_id, client_request_id)` 唯一约束争抢执行权。 |
| `project-backend/src/main/java/com/example/service/ai/impl/JdbcAgentRunService.java` | 请求绑定消息与会话指纹；完成的请求回放结果，执行中/中断的请求拒绝重跑；所有对外查询按登录用户隔离。工具执行意图先提交，终态禁止新增步骤，已经在途的步骤允许补记迟到结果。 |
| `project-backend/src/main/java/com/example/service/ai/impl/AiOrchestrationCore.java` | 同步和流式共用任务入口，回复先保存再返回；`aiTraceId` 对应持久化 `run_id`。 |
| `project-backend/src/main/java/com/example/service/ai/impl/NativeToolAudit.java` | 工具开始/结束/异常写入步骤表；未获完整结果明确标记待核实，不声称已经回滚。 |
| `project-backend/src/main/java/com/example/service/ai/impl/AiChatStreamDriver.java` | 客户端断线持久化中断；活跃流每 15 秒续心跳；结果落库失败时结束连接，避免客户端永远等待终态。 |
| `project-backend/src/main/java/com/example/controller/AiController.java` | 增加本人任务详情、会话最近任务查询接口。 |
| `project-frontend/src/pages/AiAssistantPage.vue`、`src/api/ai.ts` | 断线或缺失终态时查询原任务，取消静默同步重发；增加“查询执行结果”、步骤状态展示；刷新当前标签页后从后端恢复最近 20 次记录。 |

任务状态为 `RUNNING / SUCCEEDED / FAILED / INTERRUPTED`。步骤状态为 `RUNNING / SUCCEEDED / FAILED / UNKNOWN`。任务完成表示回复已完成校验和保存，不保证每个工具都成功，应结合步骤结果查看。超过 5 分钟未更新心跳的运行任务，在查询或重复提交时标记中断，不会抢占重跑。中断不等于撤销，已在途的业务事务仍可能提交。

**验证效果（2026-11-26）**：独立 MySQL 8.0.46 上 `AgentRunMysqlIT` 的 **14 项集成验证全部通过**，无跳过；8 个并发同请求仅 1 个取得执行权、7 个冲突；已保存结果经新服务实例、同步和流式入口回放时不再次调用模型。覆盖参数篡改、用户隔离、大小写标识、断线中断、后续工具拦截、迟到结果、心跳过期、流式终态前落库和落库失败时终止连接。原有选定的 **29 项确定性回归通过**；前端 `pnpm run build`（含类型检查）通过。模型使用确定性替身，不是模型购物成功率或线上性能数据。

从后端目录复现（Windows 本机 MySQL 8.0、JDK 21、Maven）：

```powershell
.\scripts\test-cart-mysql.ps1 -TestClass AgentRunMysqlIT -JavaHome '<JDK 21 目录>'
mvn "-Dtest=CartRequestRetryTest,AiRequestIdentityTest,AiGroundingValidatorTest,NativeToolPolicyTest,CartToolGovernanceTest" test
```

结果与源码摘要归档于 [任务 MySQL 验证报告](../../project-backend/verification/agent-run-mysql-2026-11-26.json)。脚本保存原始 JUnit、Maven 日志和清理结果，测试结束已删除临时库、关闭临时实例并清理数据目录。

**阶段说明**：以上是任务持久化阶段的验收记录。当时仅支持保存回复的恢复；业务对账、受控原步骤恢复及浏览器验收已在下节完成。历史报告保留当时的验证范围与源码摘要。

**简历表述依据**：针对流式断线后重复执行及执行结果不可追踪问题，设计基于 MySQL 的任务与工具步骤持久化机制，通过请求唯一约束、参数指纹和结果回放限制重复执行；在 8 并发同请求实验中仅放行 1 次执行，完成 14 项真实数据库集成验证，覆盖用户隔离、断线中断和迟到结果保留。

## 第一项改造收尾：购物任务可追踪、失败可恢复

截至 2026-11-26，第一项改造在“请求去重、步骤追踪、已提交业务结果核对、受控原步骤恢复”的范围内完成代码与隔离环境验收。后续两项改造独立为“订单预览与用户确认闭环”和“Agent 工程验证与业务完整性评价体系”。

**问题**：购物车事务可能已经提交，但进程在保存工具结果之前中断；只查任务表仍会得到 `UNKNOWN`。直接重跑模型可能换商品或重复购买，无限制重试还可能把库存不足等业务拒绝当成临时故障。

**方法**：

1. `CartOperationIdentity` 统一正常工具执行、业务对账和恢复的操作标识与参数指纹。查询任务时，`AgentStepReconciler` 按用户、原操作标识及指纹查找已提交购物车记录，验证结果后补记成功；不根据“记录不存在”推断已经回滚。
2. `AgentRecoveryService` 只读取服务器保存的工具名称及原参数，核对归属、原任务终态、前序步骤、白名单和剩余次数。已成功步骤只返回结果；参数冲突、业务拒绝和未解决的前序步骤阻止执行。每个步骤最多恢复两次，恢复次数与每次尝试先持久化，再运行工具。
3. `FrozenToolExecutor` 不调用模型，只支持购物车及已有只读查询工具；订单预览、确认下单和支付均不在恢复白名单。库存查询保留候选商品来源约束。购物车写恢复要求用户确认原操作，并复用原幂等标识，由 MySQL 唯一键仲裁原执行与恢复执行的竞争。即使原操作仍在途，也不通过换标识绕过去重。
4. 恢复执行权有效期两分钟，进程中断后可在剩余次数内重新申请。旧轮次回调不能覆盖新轮次或已核实成功的结果；每次恢复留下独立记录。业务条件重新检查，库存不足或商品下架停止恢复，临时查询故障保留有限重试资格。
5. 前端提供“查询执行结果”和“恢复此步骤”，确认框展示原商品、操作和数量，恢复后展示结果来源与次数；刷新从数据库恢复记录。恢复请求自己丢失响应时，只查询状态，不递归重发写请求。业务记录保存的是当次操作结果快照，当前购物车仍应由购物车接口查询。

新增/关键修改位置：

| 文件（相对于项目根目录） | 作用 |
| --- | --- |
| `project-backend/src/main/java/com/example/ai/tool/support/CartOperationIdentity.java` | 三条路径共享操作身份和指纹，避免参数漂移。 |
| `project-backend/src/main/java/com/example/service/ai/impl/AgentStepReconciler.java` | 找回提交后丢失的业务结果，不触发业务写入。 |
| `project-backend/src/main/java/com/example/service/ai/impl/AgentRecoveryService.java` | 恢复资格、确认检查、并发控制、两次上限、尝试记录。 |
| `project-backend/src/main/java/com/example/service/ai/impl/FrozenToolExecutor.java` | 按保存参数执行工具，复用业务校验，不重新规划。 |
| `project-backend/src/main/java/com/example/ai/tool/CartTool.java` | 区分业务拒绝与系统故障；业务异常先让事务回滚，再转结构化结果，避免吞异常引发 `UnexpectedRollbackException`。 |
| `project-backend/src/main/java/com/example/ai/tool/OrderQueryTool.java` | 临时查询故障与业务拒绝分别分类，保留正确的恢复资格。 |
| `project-backend/src/main/resources/db/migration/V10__create_agent_step_recovery.sql` | 增加结果来源、恢复次数和独立尝试表。 |
| `project-backend/src/main/java/com/example/controller/AiController.java` | 用户作用域内的恢复接口；请求体只含确认标记，不接受替换执行参数。 |
| `project-frontend/src/pages/AiAssistantPage.vue` | 原操作确认、结果来源、恢复次数、记录展示与页面刷新恢复。 |

**实测效果**：

| 验证 | 结果 | 覆盖范围 |
| --- | --- | --- |
| `AgentRecoveryMysqlIT` | 21/21 通过 | Flyway V1–V10、真实 MyBatis、事务代理后的 CartService、购物车表、对账、并发恢复、库存/上架校验、MVC 接口、隔离与恢复上限；订单查询临时故障用确定性故障替身。 |
| `AgentRunMysqlIT` | 14/14 通过 | 任务去重、回复回放、断线与心跳、用户隔离、步骤记录及流式异常收尾。 |
| `CartOperationMysqlIT` | 6/6 通过 | 真实数据库业务幂等、唯一约束、并发、事务回滚；业务写入使用测试计数表。 |
| 选定确定性回归 | 29/29 通过 | 请求身份、真实工具回调标识传递、事实校验与工具策略。 |
| 前端构建 | 通过 | `pnpm run build` 含 TypeScript 检查。 |
| 浏览器验收 | 1 条用户路径通过 | 实际前端页面确认原步骤 → 恢复成功 → 刷新保留记录 → 购物车数量 1、业务记录 1、库存 10。 |

合计 **70 项后端自动化用例通过，无失败或跳过**，另完成浏览器验收。并发任务入口及并发恢复均使用 8 个竞争请求验证，只放行 1 个执行者。提交后丢失审计结果可从业务记录找回，恢复再丢失响应也未重复加购。以上是指定故障场景的确定性结果，不是线上成功率或真实模型购物成功率。

浏览器夹具运行真实前端 `dist`、MVC 控制器、购物车服务与独立 MySQL；HTTP 使用仅绑定回环地址的测试适配器，登录身份为测试替身，不将其描述为生产 JWT 或真实模型全链路验收。测试结束已关闭所有本轮临时数据库实例并删除临时库与数据目录，日志保留。

**复现**（后端目录，Windows 本机 MySQL 8.0、JDK 21、Maven；脚本的 Maven/JDK 路径可覆盖）：

```powershell
.\scripts\test-cart-mysql.ps1 -TestClass AgentRecoveryMysqlIT -JavaHome '<JDK 21 目录>'
.\scripts\test-cart-mysql.ps1 -TestClass AgentRunMysqlIT -JavaHome '<JDK 21 目录>'
.\scripts\test-cart-mysql.ps1 -TestClass CartOperationMysqlIT -JavaHome '<JDK 21 目录>'
mvn "-Dtest=CartRequestRetryTest,AiRequestIdentityTest,AiGroundingValidatorTest,NativeToolPolicyTest,CartToolGovernanceTest" test
```

浏览器复现：先在前端执行 `pnpm run build`，再从后端执行 `test-cart-mysql.ps1 -TestClass AgentRecoveryBrowserIT`。读取 `target/recovery-browser-url.txt`，在十分钟内打开测试页，确认恢复、刷新核对、进入购物车，再打开同源 `/__test/finish` 完成数据库断言与清理；超时会失败并清理。测试账号仅对该临时回环服务有效。

[最终验证报告](../../project-backend/verification/task-recovery-final-2026-11-26.json)记录原始日志位置、测试名称、清理状态和源码摘要。[恢复记录截图](../../project-backend/verification/recovery-browser-step.jpg)、[购物车核对截图](../../project-backend/verification/recovery-browser-cart.jpg)保留浏览器证据。

**完成边界与部署**：恢复的是已保存的业务步骤，不自动生成缺失的后续计划，也不会重做订单预览、下单或支付。原模型任务仍保留中断/失败状态，步骤恢复不会伪造一条“整段任务已完成”的模型回复。未核实、次数耗尽、参数冲突或业务条件不满足时明确交给用户处理。迁移只在隔离测试库验证；已有业务环境启用本功能前，需要按其实际 Flyway 历史应用 V8–V10 并部署配套前后端。原业务数据未修改。

**可用于简历的表述**：针对购物 Agent 流式断线与进程中断造成的重复操作、执行结果丢失问题，设计基于 MySQL 的任务/步骤持久化、业务幂等和结果对账机制，并实现按原参数的受控步骤恢复；在 8 并发重试及提交后响应丢失实验中保持单次业务写入，完成 70 项后端自动化验证及浏览器恢复路径验收。

### 第二项改造：订单预览与用户确认闭环

**问题**：AI 页面原先只跳转到订单列表，用户在订单页再次发起结算时会生成另一份预览；每次确认使用新幂等键，草稿依赖 Redis TTL 和提交后删除，无法持久回答“这份预览是否已成交”。价格检查与扣库存之间未锁住商品，存在校验后价格变化的窗口；确认事务的订单唯一键冲突分支还可能在扣库存后直接返回其他订单。

**方法与代码位置**：

| 文件 | 改造及原因 |
| --- | --- |
| `project-backend/src/main/resources/db/migration/V11__persist_order_confirmation.sql` | 新增 `order_drafts`，保存不可变商品/金额快照、SHA-256 指纹、用户、有效期、确认键、订单 ID 和确认时间；唯一键与状态约束记录确认事实。 |
| `project-backend/src/main/java/com/example/service/order/impl/OrderDraftStore.java` | 使用同一数据源持久化预览、按用户读取、锁定草稿、绑定确认键并保存成交结果。指纹绑定预览内容，不是身份认证凭证。 |
| `project-backend/src/main/java/com/example/service/order/impl/JdbcOrderDraftService.java` | 替换 `RedisOrderDraftService`；创建预览不扣库存；GET 原预览不生成新草稿，返回 PREVIEWED / STALE / EXPIRED / CANCELLED / CONFIRMED 及原因。STALE、EXPIRED 为查询时推导的状态。 |
| `project-backend/src/main/java/com/example/service/order/impl/OrderServiceImpl.java` | `confirmDraft` 在同一事务锁草稿、购物车和按 ID 排序的商品；校验快照、价格、商品描述、数量、总金额及有效期，原子提交订单/明细、扣库存、清购物车和草稿确认状态。写入后的冲突必须抛异常回滚。 |
| `project-backend/src/main/java/com/example/mapper/ProductMapper.java` | 新增商品 `FOR UPDATE` 查询，锁持续到确认事务结束，消除价格校验与扣库存之间的竞态。 |
| `project-backend/src/main/java/com/example/controller/OrderController.java`、`dto/order/Request/ConfirmOrderRequest.java` | 新增原预览查询；确认请求必须携带 `confirmed: true`、原预览 `fingerprint` 和 `Idempotency-Key`。身份仍由认证层注入，模型工具不暴露确认/支付能力，原直接下单 HTTP 入口仍保持禁用。 |
| `project-frontend/src/pages/AiAssistantPage.vue`、`OrdersPage.vue`、`api/orders.ts`、`types/api.ts` | AI 跳转携带 `draftId`，订单页读取该原预览，展示单价/数量/小计/总价/有效期；勾选后才能确认。同一草稿使用稳定确认键，失败先 GET 查结果，刷新仍可查回原订单。 |

确认时序：`生成预览 → 查看原快照 → 明确勾选确认 → 锁定并重新校验 → 原子创建待支付订单 → 返回或查回原订单`。条件变化则终止本次确认，用户必须重新预览；不会静默按新价格成交。关闭确认窗仅表示暂不下单，不取消已创建订单。支付仍是现有独立的模拟支付步骤。

同一草稿并发确认或换确认键重试，成功后均返回同一订单；已有订单即使超过预览有效期或被取消，也不会从原草稿再创建一笔。同一用户不能用已经成功绑定的确认键提交另一份预览。确认失败时绑定随事务回滚，可以在条件满足后重试。已确认草稿的换键重放不会额外占用新键。

**接口示例**：

```http
GET /api/orders/drafts/{draftId}
Authorization: Bearer <token>

POST /api/orders/{draftId}/confirm
Authorization: Bearer <token>
Idempotency-Key: confirm-{draftId}
Content-Type: application/json

{"confirmed":true,"fingerprint":"<原预览返回的64位指纹>"}
```

`confirmedOrderId` 非空时可读取 `/api/orders/{id}`；查询不创建订单。确认失败/超时也先查询原预览，再决定是否用相同草稿和确认键重试。

**效果与验收（2026-11-26）**：

| 验证 | 结果 | 证据范围 |
| --- | --- | --- |
| `OrderConfirmationMysqlIT` | 23/23，失败、错误、跳过均为 0 | MySQL 8.0.46、V1–V11、真实 MyBatis 和 Spring 事务；并发重复确认、同键不同草稿、用户隔离、过期、快照变化、写入故障回滚、重建服务实例后重放、HTTP 确认参数校验。 |
| 本次已有功能回归 | 50/50 | 29 项 Agent 单元/编排测试 + 21 项真实数据库恢复测试；验证新增迁移后第一项功能仍通过。 |
| 前端 | 类型检查及生产构建通过 | `pnpm run build`；仍有原有大体积 bundle 提示。 |
| 浏览器验收 | 1 条交互路径通过 | 实际前端 → AI 原预览跳转 → 未勾选禁止提交 → 显式确认 → 故意丢弃成功响应并返回 504 → GET 查回 → 刷新仍是同一订单。最终原草稿 1、订单 1、库存 10→8、购物车清空。 |

8 个并发请求分别以相同键和不同键确认同一预览，均仅生成 1 笔订单、扣减 1 次库存。第二件商品库存不足、订单明细写入故障、订单唯一键冲突时，已发生的写入整体回滚。测试不依赖 Redis 或在线模型。浏览器夹具使用临时登录身份和预置模型回复，确认接口/服务/数据库及前端均执行真实代码；浏览器路径需要交互驱动，不计入 73 项后端自动化用例。

复现自动化验证（后端目录，已安装 MySQL 8、JDK 21、Maven）：

```powershell
./scripts/test-cart-mysql.ps1 -TestClass OrderConfirmationMysqlIT
./scripts/test-cart-mysql.ps1 -TestClass AgentRecoveryMysqlIT
mvn '-Dtest=AiRequestIdentityTest,CartRequestRetryTest,NativeToolPolicyTest,AiGroundingValidatorTest,CartToolGovernanceTest' test
```

可用 `-MySqlBin`、`-JavaHome`、`-MavenCommand` 指定本机安装位置，用 `-MavenArguments` 传入本地仓库/settings 参数。脚本创建独立回环 MySQL，核实实际数据目录后才运行测试；测试后关闭实例、删除临时库及其数据目录，保留 `target/mysql-validation-*/maven.log`、JUnit 和清理结果。

浏览器复现：前端先 `pnpm run build`；后端运行 `./scripts/test-cart-mysql.ps1 -TestClass OrderConfirmationBrowserIT`，读取 `target/order-browser-url.txt`。十分钟内完成 AI 入口跳转、勾选确认、确认结果自动恢复、刷新核对，再访问同源 `/__test/finish`。夹具会断言最终数据库状态并清理。

[本次验证报告](../../project-backend/verification/order-confirmation-2026-11-26.json)保存用例名称、原始结果与代码摘要；[确认前](../../project-backend/verification/order-preview-browser.jpg)、[响应丢失后查回订单](../../project-backend/verification/order-confirmed-browser.jpg)、[刷新后](../../project-backend/verification/order-replay-browser.jpg)为浏览器截图。

**升级边界**：V11 已在隔离数据库验证，尚未应用到现有业务库；部署需按现有 Flyway 历史应用迁移并同时更新前后端。旧 Redis 草稿不会迁移，旧链接需要用户重新预览。确认 HTTP 请求体由空对象升级为显式确认和指纹，旧客户端需要同步升级。该次验收不包含生产 JWT 全链路、真实模型购物成功率或真实支付，也不声称完成第三项评价体系。

**可用于简历的表述**：针对购物 Agent 预览与成交内容脱节、重复确认和响应丢失导致的下单不确定问题，实现持久化订单快照、内容指纹绑定和显式用户确认流程，以数据库行锁与事务保证订单、库存、购物车及确认状态一致；通过 23 项真实 MySQL 集成测试，在 8 并发重复确认及成功响应丢失场景下保持单笔订单，并支持刷新查回确认结果。

### 第三项改造：Agent 工程验证与业务完整性评价体系

**问题**：已有测试分散，缺少从购物任务、工具调用到实际订单/库存/购物车状态的统一验收；接口成功或模型回复成功不能证明业务正确，也缺少版本化场景和可复现的量化报告。

**方法**：建立 16 个固定购物场景，连接真实业务工具、MySQL、MyBatis 和 Spring 事务；独立核对订单金额与明细、成交快照、库存守恒、用户隔离、购物车内容和持久 trace。引入 7 个数据库负对照，证明评分器能发现错误。统一脚本执行前端检查、后端回归、重复场景与报告器测试，生成逐样本 JSON/Markdown 及源码摘要；缺失证据、跳过测试、源码变动或清理失败时门禁失败。

**实测效果（2026-11-27）**：最终相同配置独立运行两次，每次 16 场景 × 3 次 = 48/48 样本通过，恢复样本 6/6，全部重复通过的场景 16/16；每次后端 156/156（其中含这 48 个样本），报告器 12/12，失败、错误、跳过为 0，前端类型检查及生产构建通过。使用 MySQL 8.0.46，自动执行 V1–V11，临时实例及数据已清理。前端仍有原有大 bundle 提示。

评测首次发现并修复实际缺陷：CartTool 已返回“商品库存不足”，回复校验器却因只读取成功工具结果而误拒正确回复。现允许流式及最终回复引用明确业务拒绝，但不会把拒绝当成零库存、具体库存数、价格或其他商品状态的依据。该场景首次 3/3 被误拒，修复后连续两轮各 3/3 正确解释且无业务写入；新增 4 项定向回归。首次探索与最终版本评分器有所变化，不把二者包装成严格同口径的总体性能提升实验。

**复现入口**（安装 JDK 21、Maven、MySQL 8、Python 和 Node，依赖准备完毕后）：

```powershell
Set-Location D:\houduan\shopping-agent\project-backend
./scripts/run-agent-evaluation.ps1 -JavaHome 'D:\jdk-21' -MavenCommand 'D:\apache-maven-3.9.16\bin\mvn.cmd'
```

支持 `-Repeats`、`-Baseline` 和 `-Mode live`；详细参数、场景清单、指标分母、源码位置及评测边界见[工程评价说明](../../docs/agent-engineering-evaluation.md)。[最终报告](../../project-backend/verification/agent-evaluation-2026-11-27.md)、[完整 JSON 证据](../../project-backend/verification/agent-evaluation-2026-11-27.json)、[同配置基线](../../project-backend/verification/agent-evaluation-baseline-2026-11-27.json)已归档。原始日志与 JUnit 位于报告 runId 对应的 `project-backend/target/` 目录。

**范围**：当前已测量的是 scripted 工程验收，工具计划由场景提供，业务执行真实；可选 live 驱动已实现，但本次没有配置评测专用模型凭据，因此真实模型能力标为未测量。不能据此宣称自然语言购物成功率 100%。本次未新增数据库迁移，未操作现有商城数据，也未重新进行浏览器交互验收。真实支付、生产 JWT 全链路、长对话和 RAG 召回质量不在本轮评测范围内。

**可用于简历的表述**：针对购物 Agent 缺少任务级验收、接口成功与业务正确性脱节的问题，构建版本化场景集、隔离 MySQL 实验环境与独立业务判定器，联合校验执行轨迹、订单快照、库存、购物车和用户隔离，并自动生成可比较报告；以 7 类故障负对照验证评分有效性，在 16 场景各重复 3 次的工程实验中完成 48/48 验收，通过 156 项后端与 12 项报告器测试，发现并修复库存拒绝回复误判问题。
