<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { cancelOrder, confirmOrder, fetchOrderDraft, fetchOrderDetail, fetchOrders, payOrder, previewOrder } from '@/api/orders'
import { getToken } from '@/utils/storage'
import type { OrderCloseReason, OrderDraftResponse, OrderResponse, OrderStatus, OrderSummaryResponse } from '@/types/api'

const router = useRouter(); const loading = ref(false); const creating = ref(false); const actingId = ref<number | null>(null); const orders = ref<OrderSummaryResponse[]>([]); const total = ref(0); const detail = ref<OrderResponse | null>(null); const now = ref(Date.now())
let ticker: number | undefined
const route = useRoute()
const draft = ref<OrderDraftResponse | null>(null)
const reviewed = ref(false)
const confirming = ref(false)
const previewOpen = ref(false)
const previewError = ref('')
const canConfirm = computed(() => draft.value?.status === 'PREVIEWED' && remainingSeconds(draft.value.expiresAt) > 0 && reviewed.value && !creating.value)
let previewRequest = 0
function money(v: number | string) { return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function statusText(status: OrderStatus, closeReason?: OrderCloseReason | null) { if (status === 1) return '待支付'; if (status === 2) return '已支付'; return closeReason === 'PAYMENT_TIMEOUT' ? '已超时关闭' : '已取消' }
function statusColor(status: OrderStatus) { return status === 1 ? 'gold' : status === 2 ? 'green' : 'default' }
// 后端返回的时间带微秒，先截断到毫秒再解析，避免部分浏览器解析失败。
function parseTime(value: string) { return new Date(value.replace(/(\.\d{3})\d+$/, '$1')).getTime() }
function remainingSeconds(expiresAt: string) { return Math.max(0, Math.floor((parseTime(expiresAt) - now.value) / 1000)) }
function countdownText(expiresAt: string) { const seconds = remainingSeconds(expiresAt); if (seconds <= 0) return '支付已超时'; return `剩余 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
async function load() { if (!getToken()) return; loading.value = true; try { const result = await fetchOrders({ page: 1, size: 50 }); if (result.code !== 0 || !result.data) throw new Error(result.message); orders.value = result.data.records; total.value = result.data.total } catch (error) { message.error(error instanceof Error ? error.message : '订单加载失败') } finally { loading.value = false } }
async function checkout() {
  if (creating.value || confirming.value) return
  creating.value = true
  reviewed.value = false
  previewError.value = ''
  try {
    const preview = await previewOrder()
    if (preview.code !== 0 || !preview.data) throw new Error(preview.message)
    await router.replace({ path: '/orders', query: { draftId: preview.data.draftId } })
  } catch (error) {
    message.error(error instanceof Error ? error.message : '订单预览失败')
  } finally { creating.value = false }
}
async function openPreview(id: string) {
  const request = ++previewRequest
  draft.value = null
  reviewed.value = false
  previewError.value = ''
  previewOpen.value = true
  try {
    const result = await fetchOrderDraft(id)
    if (request !== previewRequest) return
    if (result.code !== 0 || !result.data) throw new Error(result.message)
    draft.value = result.data
  } catch (error) {
    if (request === previewRequest) previewError.value = error instanceof Error ? error.message : '预览读取失败，请重试'
  }
}
async function submitConfirmation() {
  if (!canConfirm.value || confirming.value || !draft.value) return
  const original = draft.value
  confirming.value = true
  try {
    // 与草稿绑定，刷新页面、超时重试仍使用相同确认键。
    const result = await confirmOrder(original.draftId, `confirm-${original.draftId}`, original.fingerprint)
    if (result.code !== 0 || !result.data) throw new Error(result.message)
    detail.value = result.data
    previewOpen.value = false
    message.success('订单创建成功，待支付')
    await load()
  } catch (error) {
    previewError.value = error instanceof Error ? error.message : '确认结果未知'
    // 响应丢失时先只读查询，不能把超时直接当成下单失败。
    try {
      const result = await fetchOrderDraft(original.draftId)
      if (result.code === 0 && result.data) {
        draft.value = result.data
        if (result.data.confirmedOrderId) {
          await showDetail(result.data.confirmedOrderId)
          previewOpen.value = false
          message.success('已查到创建成功的订单')
          await load()
        }
      }
    } catch { previewError.value = '暂时无法查询确认结果。恢复连接后可查询结果或用同一预览重试。' }
  } finally { confirming.value = false }
}
function closePreview() {
  if (confirming.value) return
  ++previewRequest
  previewOpen.value = false
  router.replace({ path: '/orders' })
}
watch(() => route.query.draftId, (id) => {
  if (typeof id === 'string' && getToken()) void openPreview(id)
}, { immediate: true })
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
  <AppLayout><div v-if="!getToken()" class="login-prompt"><a-result status="403" title="请先登录" sub-title="登录后查看订单并完成支付"><template #extra><a-button type="primary" @click="router.push('/login')">去登录</a-button></template></a-result></div><a-card v-else class="orders-card" :bordered="false"><template #title><div class="card-title"><span>我的订单</span><a-button type="primary" :loading="creating" @click="checkout">从购物车创建订单</a-button></div></template><a-spin :spinning="loading"><a-empty v-if="orders.length === 0" description="还没有订单" /><a-list v-else :data-source="orders"><template #renderItem="{ item }"><a-list-item class="order-row" @click="showDetail(item.id)"><div><strong>{{ item.orderNo }}</strong><div class="order-date">{{ item.createdAt }} · {{ item.itemCount }} 件商品</div></div><a-tag :color="statusColor(item.status)">{{ statusText(item.status, item.closeReason) }}</a-tag><span v-if="item.status === 1" class="order-countdown" :class="{ 'order-countdown-expired': remainingSeconds(item.expiresAt) <= 0 }">{{ countdownText(item.expiresAt) }}</span><strong class="order-amount">¥{{ money(item.totalAmount) }}</strong><a-space v-if="item.status === 1"><a-button type="primary" size="small" :loading="actingId === item.id" @click.stop="pay(item)">立即支付</a-button><a-button size="small" danger :disabled="actingId === item.id" @click.stop="cancel(item)">取消订单</a-button></a-space></a-list-item></template></a-list></a-spin><div class="order-total">共 {{ total }} 笔订单</div></a-card><a-modal :open="Boolean(detail)" :footer="null" title="订单详情" @cancel="detail = null"><div v-if="detail"><div class="detail-order-head"><strong>{{ detail.orderNo }}</strong><a-tag :color="statusColor(detail.status)">{{ statusText(detail.status, detail.closeReason) }}</a-tag></div><div class="detail-meta"><div>下单时间：{{ detail.createdAt }}</div><div v-if="detail.status === 1">支付时限：{{ detail.expiresAt }}（{{ countdownText(detail.expiresAt) }}）</div><div v-if="detail.paidAt">支付时间：{{ detail.paidAt }}</div><div v-if="detail.closedAt">关闭时间：{{ detail.closedAt }}</div></div><a-list :data-source="detail.items" size="small"><template #renderItem="{ item }"><a-list-item><span>{{ item.productTitle }} × {{ item.quantity }}</span><span>¥{{ money(item.subtotal) }}</span></a-list-item></template></a-list><div class="detail-total">合计 ¥{{ money(detail.totalAmount) }}</div></div></a-modal><a-modal :open="previewOpen" title="核对订单预览" :footer="null" :closable="!confirming" :mask-closable="!confirming" @cancel="closePreview">
    <a-alert v-if="previewError" :message="previewError" type="warning" show-icon class="preview-message" />
    <template v-if="draft">
      <p>请核对商品、数量和金额。确认只创建待支付订单，不会自动支付。</p>
      <a-list :data-source="draft.items" size="small"><template #renderItem="{ item }"><a-list-item>
        <div><strong>{{ item.productTitle }}</strong><div>{{ item.brand }} {{ item.model }}</div><div>单价 ¥{{ money(item.previewUnitPrice) }} × {{ item.quantity }}</div></div>
        <strong>¥{{ money(item.previewSubtotal) }}</strong>
      </a-list-item></template></a-list>
      <div class="detail-total">合计 ¥{{ money(draft.previewTotalAmount) }}</div>
      <p>预览有效至 {{ draft.expiresAt }}。价格、商品或数量变化后需要重新预览。</p>
      <a-alert v-if="draft.confirmedOrderId" message="这份预览已创建订单，重复确认不会再次下单。" type="success" show-icon />
      <a-alert v-else-if="draft.status !== 'PREVIEWED' || remainingSeconds(draft.expiresAt) <= 0" :message="draft.unavailableReason || '预览已过期，请重新预览并确认'" type="warning" show-icon />
      <a-checkbox v-else v-model:checked="reviewed" :disabled="confirming">我已核对商品、数量与总金额，确认创建订单</a-checkbox>
      <a-space wrap class="preview-actions">
        <a-button v-if="draft.confirmedOrderId" type="primary" @click="previewOpen = false; showDetail(draft.confirmedOrderId)">查看已创建订单</a-button>
        <a-button v-else type="primary" :loading="confirming" :disabled="!canConfirm" @click="submitConfirmation">确认下单</a-button>
        <a-button :disabled="confirming || creating" @click="openPreview(draft.draftId)">查询确认结果</a-button>
        <a-button :loading="creating" :disabled="confirming" @click="checkout">重新预览购物车</a-button>
        <a-button :disabled="confirming" @click="closePreview">暂不下单</a-button>
      </a-space>
    </template>
    <a-space v-else class="preview-actions"><a-button v-if="typeof route.query.draftId === 'string'" @click="openPreview(route.query.draftId)">重新读取预览</a-button><a-button :loading="creating" @click="checkout">重新预览购物车</a-button></a-space>
  </a-modal></AppLayout>
</template>
<style scoped>
.preview-actions { margin-top: 20px; } .preview-message { margin-bottom: 16px; }
.orders-card { border-radius: 4px; }.card-title { display: flex; align-items: center; justify-content: space-between; }.order-row { cursor: pointer; }.order-row:hover { background: #f9fafb; }.order-date { margin-top: 5px; color: #98a2b3; font-size: 12px; }.order-countdown { color: #d46b08; font-size: 12px; white-space: nowrap; }.order-countdown-expired { color: #98a2b3; }.order-amount { min-width: 115px; text-align: right; }.order-total { margin-top: 18px; color: #98a2b3; font-size: 12px; }.detail-order-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }.detail-meta { margin-bottom: 12px; color: #98a2b3; font-size: 12px; line-height: 20px; }.detail-total { padding-top: 14px; border-top: 1px solid #eaecf0; color: #d92d20; text-align: right; font-size: 20px; font-weight: 700; }.login-prompt { padding: 70px 0; background: #fff; }
@media (max-width: 600px) { .card-title { gap: 10px; align-items: flex-start; }.card-title .ant-btn { white-space: normal; }.order-amount { min-width: auto; }.order-row { gap: 8px; }.order-countdown { display: none; } }
</style>
