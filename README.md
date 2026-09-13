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
│  │   · OrderDraftService   订单预览草稿（Redis，TTL 10m）    │ │
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
8. **Redis 不可用时自动降级** —— 会话记忆会退化为无历史上下文（单轮对话）；订单预览草稿与幂等能力依赖 Redis，Redis 挂掉时相关功能不可用。
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
| POST | `/api/orders/{draftId}/confirm` | 确认预览草稿并下单 | 是 |
| POST | `/api/orders` | 直接下单 | 是 |
| GET | `/api/orders` | 订单分页列表 | 是 |
| GET | `/api/orders/{id}` | 订单详情 | 是 |
| POST | `/api/orders/{id}/pay` | 支付订单 | 是 |
| POST | `/api/orders/{id}/cancel` | 取消订单 | 是 |
| POST | `/api/ai/chat` | AI 对话（同步返回） | 是 |
| POST | `/api/ai/chat/stream` | AI 对话（SSE 流式返回） | 是 |
