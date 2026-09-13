<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { cancelOrder, confirmOrder, fetchOrderDetail, fetchOrders, payOrder, previewOrder } from '@/api/orders'
import { getToken } from '@/utils/storage'
import type { OrderCloseReason, OrderResponse, OrderStatus, OrderSummaryResponse } from '@/types/api'

const router = useRouter(); const loading = ref(false); const creating = ref(false); const actingId = ref<number | null>(null); const orders = ref<OrderSummaryResponse[]>([]); const total = ref(0); const detail = ref<OrderResponse | null>(null); const now = ref(Date.now())
let ticker: number | undefined
function money(v: number | string) { return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function statusText(status: OrderStatus, closeReason?: OrderCloseReason | null) { if (status === 1) return '待支付'; if (status === 2) return '已支付'; return closeReason === 'PAYMENT_TIMEOUT' ? '已超时关闭' : '已取消' }
function statusColor(status: OrderStatus) { return status === 1 ? 'gold' : status === 2 ? 'green' : 'default' }
// 后端返回的时间带微秒，先截断到毫秒再解析，避免部分浏览器解析失败。
function parseTime(value: string) { return new Date(value.replace(/(\.\d{3})\d+$/, '$1')).getTime() }
function remainingSeconds(expiresAt: string) { return Math.max(0, Math.floor((parseTime(expiresAt) - now.value) / 1000)) }
function countdownText(expiresAt: string) { const seconds = remainingSeconds(expiresAt); if (seconds <= 0) return '支付已超时'; return `剩余 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
async function load() { if (!getToken()) return; loading.value = true; try { const result = await fetchOrders({ page: 1, size: 50 }); if (result.code !== 0 || !result.data) throw new Error(result.message); orders.value = result.data.records; total.value = result.data.total } catch (error) { message.error(error instanceof Error ? error.message : '订单加载失败') } finally { loading.value = false } }
async function checkout() {
  creating.value = true
  try {
    const preview = await previewOrder()
    if (preview.code !== 0 || !preview.data) throw new Error(preview.message)
    const draft = preview.data
    const itemText = draft.items.map((item) => `${item.productTitle} × ${item.quantity}`).join('；')
    Modal.confirm({
      title: '确认订单',
      content: `商品：${itemText}。预览金额 ¥${money(draft.previewTotalAmount)}，确认后将按服务端最新价格和库存创建订单。`,
      okText: '确认下单',
      cancelText: '取消',
      onOk: async () => {
        try {
          const result = await confirmOrder(draft.draftId, `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`)
          if (result.code !== 0 || !result.data) throw new Error(result.message)
          message.success('订单创建成功')
          detail.value = result.data
          await load()
        } catch (error) {
          message.error(error instanceof Error ? error.message : '订单确认失败')
          throw error
        } finally {
          creating.value = false
        }
      },
      onCancel: () => { creating.value = false },
    })
  } catch (error) {
    creating.value = false
    message.error(error instanceof Error ? error.message : '订单预览失败')
  }
}
async function showDetail(id: number) { try { const result = await fetchOrderDetail(id); if (result.code !== 0 || !result.data) throw new Error(result.message); detail.value = result.data } catch (error) { message.error(error instanceof Error ? error.message : '订单详情加载失败') } }
function pay(order: OrderSummaryResponse) {
  Modal.confirm({
    title: '确认支付',
    content: `支付订单 ${order.orderNo}，金额 ¥${money(order.totalAmount)}。本项目为模拟支付，不会产生真实扣款。`,
    okText: '确认支付',
    cancelText: '再想想',
    onOk: async () => {
      actingId.value = order.id
      try {
        const result = await payOrder(order.id)
        if (result.code !== 0 || !result.data) throw new Error(result.message)
        message.success('支付成功')
        await load()
      } catch (error) {
        message.error(error instanceof Error ? error.message : '支付失败')
        throw error
      } finally {
        actingId.value = null
      }
    },
  })
}
function cancel(order: OrderSummaryResponse) {
  Modal.confirm({
    title: '确认取消订单',
    content: `取消订单 ${order.orderNo} 后将归还已占用库存，订单不可恢复。`,
    okText: '确认取消',
    okType: 'danger',
    cancelText: '再想想',
    onOk: async () => {
      actingId.value = order.id
      try {
        const result = await cancelOrder(order.id)
        if (result.code !== 0 || !result.data) throw new Error(result.message)
        message.success('订单已取消，库存已归还')
        await load()
      } catch (error) {
        message.error(error instanceof Error ? error.message : '取消订单失败')
        throw error
      } finally {
        actingId.value = null
      }
    },
  })
}
onMounted(() => { load(); ticker = window.setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { if (ticker !== undefined) window.clearInterval(ticker) })
</script>
<template>
  <AppLayout><div v-if="!getToken()" class="login-prompt"><a-result status="403" title="请先登录" sub-title="登录后查看订单并完成支付"><template #extra><a-button type="primary" @click="router.push('/login')">去登录</a-button></template></a-result></div><a-card v-else class="orders-card" :bordered="false"><template #title><div class="card-title"><span>我的订单</span><a-button type="primary" :loading="creating" @click="checkout">从购物车创建订单</a-button></div></template><a-spin :spinning="loading"><a-empty v-if="orders.length === 0" description="还没有订单" /><a-list v-else :data-source="orders"><template #renderItem="{ item }"><a-list-item class="order-row" @click="showDetail(item.id)"><div><strong>{{ item.orderNo }}</strong><div class="order-date">{{ item.createdAt }} · {{ item.itemCount }} 件商品</div></div><a-tag :color="statusColor(item.status)">{{ statusText(item.status, item.closeReason) }}</a-tag><span v-if="item.status === 1" class="order-countdown" :class="{ 'order-countdown-expired': remainingSeconds(item.expiresAt) <= 0 }">{{ countdownText(item.expiresAt) }}</span><strong class="order-amount">¥{{ money(item.totalAmount) }}</strong><a-space v-if="item.status === 1"><a-button type="primary" size="small" :loading="actingId === item.id" @click.stop="pay(item)">立即支付</a-button><a-button size="small" danger :disabled="actingId === item.id" @click.stop="cancel(item)">取消订单</a-button></a-space></a-list-item></template></a-list></a-spin><div class="order-total">共 {{ total }} 笔订单</div></a-card><a-modal :open="Boolean(detail)" :footer="null" title="订单详情" @cancel="detail = null"><div v-if="detail"><div class="detail-order-head"><strong>{{ detail.orderNo }}</strong><a-tag :color="statusColor(detail.status)">{{ statusText(detail.status, detail.closeReason) }}</a-tag></div><div class="detail-meta"><div>下单时间：{{ detail.createdAt }}</div><div v-if="detail.status === 1">支付时限：{{ detail.expiresAt }}（{{ countdownText(detail.expiresAt) }}）</div><div v-if="detail.paidAt">支付时间：{{ detail.paidAt }}</div><div v-if="detail.closedAt">关闭时间：{{ detail.closedAt }}</div></div><a-list :data-source="detail.items" size="small"><template #renderItem="{ item }"><a-list-item><span>{{ item.productTitle }} × {{ item.quantity }}</span><span>¥{{ money(item.subtotal) }}</span></a-list-item></template></a-list><div class="detail-total">合计 ¥{{ money(detail.totalAmount) }}</div></div></a-modal></AppLayout>
</template>
<style scoped>
.orders-card { border-radius: 4px; }.card-title { display: flex; align-items: center; justify-content: space-between; }.order-row { cursor: pointer; }.order-row:hover { background: #f9fafb; }.order-date { margin-top: 5px; color: #98a2b3; font-size: 12px; }.order-countdown { color: #d46b08; font-size: 12px; white-space: nowrap; }.order-countdown-expired { color: #98a2b3; }.order-amount { min-width: 115px; text-align: right; }.order-total { margin-top: 18px; color: #98a2b3; font-size: 12px; }.detail-order-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }.detail-meta { margin-bottom: 12px; color: #98a2b3; font-size: 12px; line-height: 20px; }.detail-total { padding-top: 14px; border-top: 1px solid #eaecf0; color: #d92d20; text-align: right; font-size: 20px; font-weight: 700; }.login-prompt { padding: 70px 0; background: #fff; }
@media (max-width: 600px) { .card-title { gap: 10px; align-items: flex-start; }.card-title .ant-btn { white-space: normal; }.order-amount { min-width: auto; }.order-row { gap: 8px; }.order-countdown { display: none; } }
</style>
