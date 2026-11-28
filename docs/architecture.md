# 架构与改造边界

以下图对应当前实现。商城、JWT、Spring AI 工具调用、RAG 与 Redis 记忆属于继承的基础；标注“改造”的部分为本次增量。架构图表示逻辑依赖，不代表已完成生产部署或所有外部服务的端到端验收。

## 整体架构

```mermaid
flowchart TD
    UI[Vue 商城与 AI 页面：继承基础并增强交互] --> API[Spring Boot 接口与 JWT 身份：已有基础]
    API --> CORE[AI 编排核心：已有基础并扩展]
    CORE --> MODEL[Spring AI 与模型工具调用：已有基础]
    CORE --> MEM[Redis 会话记忆：已有基础]
    MODEL --> TOOLS[受审计的业务工具]
    TOOLS --> RAG[Milvus 召回与 MySQL 回查：已有基础]
    TOOLS --> BIZ[商品与购物车业务服务]
    CORE --> RUN[改造一：任务与步骤持久化]
    API --> REC[改造一：查结果、对账、受控恢复]
    REC --> RUN
    REC --> BIZ
    BIZ --> DB[(MySQL 业务数据与幂等记录)]
    RUN --> DB
    TOOLS --> DRAFT[改造二：持久化订单预览]
    API --> CONFIRM[改造二：用户确认与原子成交]
    DRAFT --> DB
    CONFIRM --> DB
    EVAL[改造三：固定场景与隔离 MySQL 实验] -. 验证真实实现 .-> CORE
    EVAL -. 验证真实实现 .-> CONFIRM
    EVAL --> ORACLE[SQL 业务判定、trace 核对与负对照]
    ORACLE --> REPORT[JSON / Markdown 指标与基线报告]
```

模型可调用查询、购物车和预览工具；没有确认下单或支付工具。确认接口从服务端认证上下文取得用户身份，不能通过工具参数指定另一个用户。

## 改造一：结果先查询，恢复按原参数执行

```mermaid
sequenceDiagram
    actor U as 用户
    participant F as AI 页面
    participant A as 编排核心
    participant R as 任务与步骤记录
    participant T as 业务工具
    participant D as MySQL
    U->>F: 提交购物请求
    F->>A: 消息与稳定 clientRequestId
    A->>R: 按用户与请求标识争取执行权
    alt 已有保存结果
        R-->>F: 回放原结果，不重新调用模型
    else 新任务
        A->>R: 先保存步骤意图与参数
        A->>T: 执行业务工具
        T->>D: 同事务写购物车与幂等记录
        A->>R: 保存步骤结果与最终回复
        A-->>F: 返回结果
    end
    Note over F,D: 连接中断不代表业务回滚
    F->>R: 查询原任务
    R->>D: 查询已提交业务记录并对账
    alt 已找到成功结果
        R-->>F: 返回核实结果，无重复写入
    else 仍符合恢复资格
        U->>F: 确认原操作
        F->>R: 请求恢复原步骤
        R->>T: 白名单工具、原参数、受限次数
        T->>D: 复用幂等标识及业务校验
    end
```

恢复不是重新运行整个 Agent，也不会让模型生成替代参数。每个步骤最多 2 次恢复尝试；订单预览、确认和支付不走通用步骤恢复。步骤恢复成功后，原任务仍可以保持 INTERRUPTED，避免伪造整段任务完成。

入口：[JdbcAgentRunService](../project-backend/src/main/java/com/example/service/ai/impl/JdbcAgentRunService.java)、[AgentRecoveryService](../project-backend/src/main/java/com/example/service/ai/impl/AgentRecoveryService.java)、[AgentStepReconciler](../project-backend/src/main/java/com/example/service/ai/impl/AgentStepReconciler.java)。

## 改造二：原预览与确认结果绑定

```mermaid
sequenceDiagram
    actor U as 用户
    participant AI as AI 页面
    participant P as 订单页面
    participant S as 订单服务
    participant DB as MySQL
    AI->>S: 生成预览
    S->>DB: 保存快照、指纹、用户与有效期
    S-->>AI: draftId 与预览内容
    AI->>P: 携带原 draftId 跳转
    P->>S: 读取原预览
    U->>P: 核对并显式确认
    P->>S: confirmed、fingerprint、稳定确认键
    S->>DB: 锁草稿、购物车与商品
    alt 已确认
        S-->>P: 返回原订单
    else 条件变化或已过期
        S-->>P: 拒绝成交，要求重新预览
    else 校验通过
        S->>DB: 同事务写订单和明细、扣库存、清购物车、绑定确认结果
        S-->>P: 返回待支付订单
    end
    Note over P,DB: 若确认响应丢失，先查询原预览的成交结果
    P->>S: GET 原预览
    S-->>P: confirmedOrderId
```

预览不预占库存；确认时必须再次核对条件。快照指纹用于识别内容变化，不代替用户认证。确认只创建待支付订单，不自动支付。

入口：[OrderDraftStore](../project-backend/src/main/java/com/example/service/order/impl/OrderDraftStore.java)、[JdbcOrderDraftService](../project-backend/src/main/java/com/example/service/order/impl/JdbcOrderDraftService.java)、[OrderServiceImpl](../project-backend/src/main/java/com/example/service/order/impl/OrderServiceImpl.java)。

## 改造三：验证行为与业务事实

```mermaid
flowchart LR
    CASE[版本化场景与固定数据] --> DRIVER{驱动模式}
    DRIVER --> SCRIPT[scripted：注入工具计划]
    DRIVER --> LIVE[live：可选真实模型]
    SCRIPT --> EXEC[生产工具、事务、任务记录]
    LIVE --> EXEC
    EXEC --> CHECK[预期状态、跨表不变量、执行记录核对]
    NEG[故意制造坏数据的负对照] --> CHECK
    CHECK --> GATE[检查样本、JUnit、源码摘要及清理结果]
    GATE --> RESULT[指标、失败原因、兼容基线比较]
```

默认 scripted 验证工程行为，不证明模型自主选工具能力。live 入口已实现，本轮尚未执行；它使用专用非流式传输层，也不等于生产 SSE 全链路测试。具体指标分母与边界见 [工程评价说明](agent-engineering-evaluation.md)。
