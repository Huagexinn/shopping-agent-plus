# Shopping Agent AI 前端

当前前端基于 Vue 3、Vite、TypeScript、Vue Router、Pinia、Ant Design Vue 和 Axios，已提供手机商品公开查询 API 封装。

## 本地开发

```bash
pnpm install
cp .env.example .env.local
pnpm dev
```

默认 API 地址为 `/api`。如果前后端分别运行，可在 `.env.local` 中设置：

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api
```

## 验证构建

```bash
pnpm build
```

首页提供商品浏览、详情查看和加入购物车，按后端统一的 `code/message/data` 结构处理结果。

商品 API 封装位于 `src/api/products.ts`，类型位于 `src/types/api.ts`，提供：

```text
GET /api/products
GET /api/products/{id}
```

`http` 已统一配置 `/api` 基地址，因此封装内部使用 `/products` 和 `/products/{id}`，不会在请求中重复拼接前缀。价格类型兼容后端 `BigDecimal` 的数字或字符串序列化；库存、状态和金额只展示服务端返回值，前端不计算可信库存或订单金额。

已实现登录、注册、购物车和订单页面；订单创建使用后端要求的 `Idempotency-Key`。AI 对话接口尚未在当前后端提供，因此前端暂不展示 AI 对话入口。
