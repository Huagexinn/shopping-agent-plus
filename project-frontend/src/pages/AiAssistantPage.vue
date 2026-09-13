<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { message } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  DownOutlined,
  FileTextOutlined,
  FireOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  SettingOutlined,
  ShoppingOutlined,
  ThunderboltOutlined,
  UserOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { sendAiChat, streamAiChat } from '@/api/ai'
import { getToken } from '@/utils/storage'
import { renderMarkdown } from '@/utils/markdown'
import type { AiChatRequest, AiChatResponse } from '@/types/api'

type TurnStatus = 'sending' | 'success' | 'error'

interface ChatTurn {
  id: string
  createdAt: string
  status: TurnStatus
  request: AiChatRequest
  response: AiChatResponse | null
  /**
   * 已渲染的流式正文。收到 replace 时整体替换，收到 final 时用 final.message 覆盖 ——
   * 后端保证 final.message 是权威文本：正常时逐字等于流式拼接结果，事实校验拒绝时就是替换文本。
   */
  streamedText: string
  /** 是否走了同步降级，用于在回复下方给一条温和提示。 */
  degraded: boolean
  error: string | null
}

const router = useRouter()
const isLoggedIn = computed(() => Boolean(getToken()))
const conversationId = ref(createConversationId())
const turns = ref<ChatTurn[]>([])
const expandedTurn = ref<string | null>(null)
const chatScroll = ref<HTMLElement | null>(null)
const inFlight = ref<AbortController | null>(null)
const quickPrompts = [
  '5000 元以内，拍照和续航都要好',
  '帮我看看 Samsung Galaxy S25 有没有现货',
  '推荐 3000 元以内、12GB+256GB 的手机',
  '预算充足，哪款 iPhone 最值得买？',
]
const draft = reactive<AiChatRequest>({
  conversationId: conversationId.value,
  message: '',
})

const chatTurns = computed(() => turns.value)
const requestPending = computed(() => turns.value.some((turn) => turn.status === 'sending'))

function createConversationId() {
  return `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function abortInFlight() {
  inFlight.value?.abort()
  inFlight.value = null
}

function newConversation() {
  abortInFlight()
  const nextConversationId = createConversationId()
  conversationId.value = nextConversationId
  draft.conversationId = nextConversationId
  draft.message = ''
  turns.value = []
  expandedTurn.value = null
}

onBeforeUnmount(abortInFlight)

function usePrompt(prompt: string) {
  draft.message = prompt
}

function formatTime(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function toolColor(toolName: string) {
  if (toolName.includes('Search')) return 'blue'
  if (toolName.includes('Budget')) return 'gold'
  if (toolName.includes('Inventory')) return 'green'
  if (toolName.includes('Recommendation')) return 'purple'
  return 'default'
}

function toggleEvidence(id: string) {
  expandedTurn.value = expandedTurn.value === id ? null : id
}

function goToOrderConfirmation() {
  router.push('/orders')
}

function resultStatusLabel(status: AiChatResponse['resultStatus']) {
  switch (status) {
    case 'MODEL_TIMEOUT': return '模型超时'
    case 'MODEL_ERROR': return '模型不可用'
    case 'EMPTY_RESPONSE': return '模型返回为空'
    case 'TOOL_ERROR': return '商城数据查询失败'
    case 'GROUNDING_REJECTED': return '事实校验未通过'
    default: return '已完成'
  }
}

function toolStatusLabel(status?: string, success?: boolean) {
  switch (status) {
    case 'PARTIAL_SUCCESS': return '部分完成'
    case 'BUSINESS_FAILURE': return '条件未满足'
    case 'SYSTEM_FAILURE': return '系统失败'
    case 'SUCCESS': return '已完成'
    default: return success ? '已完成' : '未完成'
  }
}

function updateTurn(id: string, patch: Partial<ChatTurn>) {
  turns.value = turns.value.map((turn) => (turn.id === id ? { ...turn, ...patch } : turn))
}

/** 展示文本优先用流式正文；final 到达后它已被权威文本覆盖。 */
function turnText(turn: ChatTurn) {
  return turn.streamedText || turn.response?.message || ''
}

function turnFailed(turn: ChatTurn) {
  return Boolean(turn.response && turn.response.resultStatus !== 'SUCCESS')
}

async function scrollToLatest() {
  await nextTick()
  chatScroll.value?.scrollTo({ top: chatScroll.value.scrollHeight, behavior: 'smooth' })
}

async function copyConversationId() {
  await navigator.clipboard?.writeText(draft.conversationId)
  message.success('会话 ID 已复制')
}

async function submit() {
  if (!isLoggedIn.value) {
    message.info('请先登录后再和 AI 助手对话')
    return
  }
  if (requestPending.value) return
  const text = draft.message.trim()
  if (!text) {
    message.warning('先告诉我你想买什么手机')
    return
  }
  const payload: AiChatRequest = { conversationId: draft.conversationId.trim(), message: text }
  const id = `turn-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  turns.value.push({
    id,
    createdAt: new Date().toISOString(),
    status: 'sending',
    request: payload,
    response: null,
    streamedText: '',
    degraded: false,
    error: null,
  })
  draft.message = ''
  await scrollToLatest()

  const controller = new AbortController()
  inFlight.value = controller
  let rendered = false
  let terminal = false

  try {
    await streamAiChat(payload, {
      onTextDelta: (delta) => {
        if (!delta) return
        rendered = true
        const current = turns.value.find((turn) => turn.id === id)
        updateTurn(id, { streamedText: (current?.streamedText || '') + delta })
        void scrollToLatest()
      },
      onReplace: (replaced) => {
        // 校验中断：已渲染的内容全部作废，整体替换
        rendered = true
        updateTurn(id, { streamedText: replaced.message })
        void scrollToLatest()
      },
      onFinal: (response) => {
        terminal = true
        const failed = response.resultStatus !== 'SUCCESS'
        updateTurn(id, {
          status: failed ? 'error' : 'success',
          response,
          streamedText: response.message,
          error: failed ? resultStatusLabel(response.resultStatus) : null,
        })
      },
      onError: (error) => {
        terminal = true
        updateTurn(id, { status: 'error', error: error.message, streamedText: '' })
        message.error(error.message)
      },
    }, controller.signal)
    await scrollToLatest()
  } catch (error) {
    if (controller.signal.aborted) {
      // 用户主动取消（新对话 / 离开页面），不算失败
      return
    }
    if (rendered || terminal) {
      // 已经渲染过正文才断开：不能用同步接口重发，否则同一段回复会再吐一遍
      const errorText = error instanceof Error ? error.message : '流式连接中断'
      updateTurn(id, { status: 'error', error: `回复中断：${errorText}` })
      message.error('回复中断，请重新发送')
      return
    }
    // 还没渲染任何正文就断开：静默降级到同步接口，只重试这一次
    console.warn('[ai] 流式连接不可用，降级到同步接口', error)
    await fallbackToSync(id, payload)
  } finally {
    if (inFlight.value === controller) {
      inFlight.value = null
    }
  }
}

/** 降级：用同步 JSON 接口重发同一请求。只做一次，再失败就直接展示错误。 */
async function fallbackToSync(id: string, payload: AiChatRequest) {
  try {
    const result = await sendAiChat(payload)
    if (result.code !== 0 || !result.data) {
      const errorText = result.message || 'AI 请求失败，请稍后再试'
      updateTurn(id, { status: 'error', response: result.data, error: errorText })
      message.error(errorText)
    } else {
      updateTurn(id, {
        status: 'success',
        response: result.data,
        streamedText: result.data.message,
        degraded: true,
        error: null,
      })
    }
  } catch (error) {
    const errorData = isAxiosError(error) ? error.response?.data as { message?: string; data?: AiChatResponse | null } | undefined : undefined
    const errorText = errorData?.message || (error instanceof Error ? error.message : 'AI 请求失败，请稍后再试')
    updateTurn(id, { status: 'error', response: errorData?.data || null, error: errorText })
    message.error(errorText)
  }
  await scrollToLatest()
}
</script>

<template>
  <AppLayout>
    <div v-if="!isLoggedIn" class="login-state">
      <div class="login-panel">
        <div class="login-icon"><RobotOutlined /></div>
        <p class="eyebrow dark">NUMA AI SHOPPING</p>
        <h1>让 AI 帮你选到<br /><span>真正适合的手机</span></h1>
        <p class="login-copy">告诉我预算、用途和偏好，我会结合实时商品、库存和商城知识给你一份清晰建议。</p>
        <a-button type="primary" size="large" @click="router.push('/login')">登录后开始对话 <SendOutlined /></a-button>
        <div class="login-note"><CheckCircleOutlined /> 你的对话只和当前账号关联</div>
      </div>
    </div>

    <section v-else class="assistant-page">
      <header class="assistant-header">
        <div class="assistant-heading">
          <div class="assistant-avatar"><RobotOutlined /></div>
          <div>
            <div class="heading-line"><h1>AI 选机顾问</h1><span class="online-dot">在线</span></div>
            <p>基于实时商品与库存，为你做出更放心的选择</p>
          </div>
        </div>
        <a-button class="new-chat-button" @click="newConversation"><PlusOutlined /> 新对话</a-button>
      </header>

      <div class="assistant-layout">
        <aside class="assistant-sidebar">
          <div class="sidebar-section">
            <div class="sidebar-label">当前会话</div>
            <div class="conversation-row">
              <div class="conversation-icon"><ClockCircleOutlined /></div>
              <div class="conversation-copy"><strong>新建选机对话</strong><span>{{ turns.length ? `${turns.length} 条消息` : '还没有消息' }}</span></div>
              <a-button type="text" class="more-button" title="会话设置"><SettingOutlined /></a-button>
            </div>
          </div>

          <div class="sidebar-section capability-section">
            <div class="sidebar-label">我可以帮你</div>
            <div class="capability-item"><ShoppingOutlined /><span>按预算和需求找手机</span></div>
            <div class="capability-item"><ThunderboltOutlined /><span>查询实时库存与规格</span></div>
            <div class="capability-item"><FileTextOutlined /><span>解读商品与售后政策</span></div>
          </div>

          <div class="sidebar-footer">
            <div class="sidebar-label">会话 ID</div>
            <div class="id-row"><code>{{ draft.conversationId }}</code><a-button type="text" title="复制会话 ID" @click="copyConversationId"><CopyOutlined /></a-button></div>
            <span class="privacy-note">仅用于保持本次对话上下文</span>
          </div>
        </aside>

        <main class="chat-panel">
          <div ref="chatScroll" class="chat-scroll" :class="{ 'is-empty': chatTurns.length === 0 }">
            <div v-if="chatTurns.length === 0" class="welcome-state">
              <div class="welcome-mark"><FireOutlined /></div>
              <h2>你好，我是你的 AI 选机顾问</h2>
              <p>从预算、拍照、游戏到续航，告诉我你的真实需求，剩下的交给我。</p>
              <div class="prompt-grid">
                <button v-for="prompt in quickPrompts" :key="prompt" class="prompt-card" type="button" @click="usePrompt(prompt)">
                  <span>{{ prompt }}</span><SendOutlined />
                </button>
              </div>
            </div>

            <div v-else class="message-list">
              <div v-for="turn in chatTurns" :key="turn.id" class="message-group">
                <div class="message-row user-row">
                  <div class="message-bubble user-bubble">{{ turn.request.message }}</div>
                  <div class="message-avatar user-avatar"><UserOutlined /></div>
                </div>
                <div class="message-row assistant-row">
                  <div class="message-avatar assistant-message-avatar"><RobotOutlined /></div>
                  <div class="assistant-message-wrap">
                    <div v-if="turn.status === 'sending' && !turnText(turn)" class="message-bubble assistant-bubble loading-bubble"><span></span><span></span><span></span></div>
                    <div v-else-if="turnText(turn)" class="message-bubble assistant-bubble" :class="{ 'error-bubble': turnFailed(turn) }"><div class="markdown-body" v-html="renderMarkdown(turnText(turn))"></div><span v-if="turn.status === 'sending'" class="stream-cursor"></span></div>
                    <div v-else class="message-bubble assistant-bubble error-bubble">{{ turn.error || '这次没有拿到回复，请重新试试。' }}</div>
                    <div v-if="turn.response?.orderDraft" class="draft-action">
                      <div class="draft-action-copy">
                        <strong>订单预览已准备好</strong>
                        <span>{{ turn.response.orderDraft.items.length }} 项商品 · 预览金额 ¥{{ Number(turn.response.orderDraft.previewTotalAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</span>
                      </div>
                      <a-button type="primary" size="small" @click="goToOrderConfirmation">
                        去订单页确认 <ArrowRightOutlined />
                      </a-button>
                    </div>
                    <div v-if="turn.response" class="message-meta"><span>{{ formatTime(turn.createdAt) }}</span><a-tag :color="turn.response.resultStatus === 'SUCCESS' ? 'green' : 'red'">{{ resultStatusLabel(turn.response.resultStatus) }}</a-tag><span v-if="turn.degraded" class="degraded-hint" title="流式连接中断，本条回复由同步接口返回">网络波动，本条回复已自动重试获取</span><a-tag v-if="turn.response.toolCalls.length" color="blue"><ThunderboltOutlined /> {{ turn.response.toolCalls.length }} 次工具调用</a-tag><a-button type="text" class="evidence-button" @click="toggleEvidence(turn.id)">{{ expandedTurn === turn.id ? '收起依据' : '查看依据' }} <DownOutlined :rotate="expandedTurn === turn.id ? 180 : 0" /></a-button></div>
                    <div v-if="expandedTurn === turn.id && turn.response" class="evidence-panel">
                      <div class="evidence-heading"><span>本次回答依据</span><small>来自实时业务数据与知识库</small></div>
                      <div v-if="turn.response.toolCalls.length" class="evidence-block"><div class="evidence-label">工具调用</div><div class="evidence-tags"><a-tag v-for="tool in turn.response.toolCalls" :key="`${turn.id}-${tool.toolName}-${tool.queriedAt}`" :color="toolColor(tool.toolName)">{{ tool.toolName }} · {{ toolStatusLabel(tool.resultStatus, tool.success) }}</a-tag></div></div>
                      <div v-if="turn.response.references.length" class="evidence-block"><div class="evidence-label">商品信息</div><div class="reference-list"><div v-for="reference in turn.response.references" :key="`${turn.id}-${reference.productId}`" class="reference-item"><span class="reference-index">{{ reference.productId }}</span><span>{{ reference.title || '未命名商品' }}</span></div></div></div>
                      <div v-if="turn.response.knowledgeReferences.length" class="evidence-block"><div class="evidence-label">知识库</div><div class="reference-list"><div v-for="reference in turn.response.knowledgeReferences" :key="`${turn.id}-${reference.documentId}`" class="reference-item"><FileTextOutlined /><span>{{ reference.title }}</span></div></div></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="composer-wrap">
            <div class="composer-hint"><span><CheckCircleOutlined /> AI 会以实时商品数据为准</span><span>{{ draft.message.length }}/2000</span></div>
            <a-form class="composer" @submit.prevent="submit">
              <a-textarea v-model:value="draft.message" :auto-size="{ minRows: 1, maxRows: 5 }" :maxlength="2000" placeholder="描述你的预算、用途或想了解的手机…" @pressEnter.exact.prevent="submit" />
              <a-button type="primary" shape="circle" html-type="submit" :loading="requestPending" :disabled="requestPending || !draft.message.trim()" title="发送消息"><SendOutlined /></a-button>
            </a-form>
            <div class="composer-foot"><span>Enter 发送 · Shift + Enter 换行</span><span><ReloadOutlined /> 对话内容会按会话保存</span></div>
          </div>
        </main>
      </div>
    </section>
  </AppLayout>
</template>

<style scoped>
/* AI assistant consumer chat surface */
.login-state { min-height: 620px; display: grid; place-items: center; padding: 38px 0; }.login-panel { width: min(620px, 100%); padding: 58px 54px; text-align: center; background: #fff; border: 1px solid #e4e7ec; border-radius: 18px; box-shadow: 0 18px 48px rgb(16 24 40 / 8%); }.login-icon { width: 72px; height: 72px; display: grid; place-items: center; margin: 0 auto 24px; color: #fff; background: #172b4d; border-radius: 20px; font-size: 34px; }.eyebrow { margin: 0 0 12px; color: #fdb022; font-size: 11px; font-weight: 700; letter-spacing: 2px; }.eyebrow.dark { color: #667085; }.login-panel h1 { margin: 0; color: #101828; font-size: clamp(32px, 5vw, 46px); line-height: 1.14; font-weight: 800; }.login-panel h1 span { color: #1677ff; }.login-copy { max-width: 460px; margin: 18px auto 28px; color: #667085; line-height: 1.8; }.login-note { margin-top: 18px; color: #98a2b3; font-size: 12px; }.login-note :deep(.anticon) { margin-right: 5px; color: #12b76a; }
.assistant-page { display: flex; flex-direction: column; gap: 18px; }.assistant-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.assistant-heading { display: flex; align-items: center; gap: 14px; }.assistant-avatar { width: 48px; height: 48px; display: grid; place-items: center; color: #fff; background: #172b4d; border-radius: 14px; font-size: 23px; }.heading-line { display: flex; align-items: center; gap: 10px; }.heading-line h1 { margin: 0; color: #101828; font-size: 26px; font-weight: 800; }.assistant-heading p { margin: 3px 0 0; color: #667085; font-size: 13px; }.online-dot { display: inline-flex; align-items: center; gap: 5px; color: #067647; font-size: 12px; }.online-dot::before { width: 7px; height: 7px; content: ''; border-radius: 50%; background: #12b76a; }.new-chat-button { height: 38px; border-color: #d0d5dd; color: #344054; }
.assistant-layout { display: grid; min-height: 650px; grid-template-columns: 245px minmax(0, 1fr); overflow: hidden; background: #fff; border: 1px solid #e4e7ec; border-radius: 16px; box-shadow: 0 12px 34px rgb(16 24 40 / 5%); }.assistant-sidebar { display: flex; flex-direction: column; padding: 24px 18px; background: #f8fafc; border-right: 1px solid #eaecf0; }.sidebar-section { padding-bottom: 24px; border-bottom: 1px solid #eaecf0; }.sidebar-section + .sidebar-section { padding-top: 22px; }.sidebar-label { margin-bottom: 12px; color: #98a2b3; font-size: 11px; font-weight: 700; letter-spacing: 1px; text-transform: uppercase; }.conversation-row { display: flex; align-items: center; gap: 10px; padding: 11px 10px; background: #fff; border: 1px solid #dbe3ef; border-radius: 10px; }.conversation-icon { width: 30px; height: 30px; display: grid; place-items: center; color: #1677ff; background: #eaf2ff; border-radius: 8px; }.conversation-copy { min-width: 0; flex: 1; }.conversation-copy strong, .conversation-copy span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.conversation-copy strong { color: #344054; font-size: 12px; }.conversation-copy span { margin-top: 2px; color: #98a2b3; font-size: 11px; }.more-button { color: #98a2b3; }.capability-item { display: flex; align-items: center; gap: 9px; padding: 8px 0; color: #667085; font-size: 12px; }.capability-item :deep(.anticon) { color: #1677ff; font-size: 15px; }.sidebar-footer { margin-top: auto; padding-top: 20px; }.id-row { display: flex; align-items: center; gap: 5px; }.id-row code { min-width: 0; flex: 1; overflow: hidden; color: #475467; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.id-row .ant-btn { color: #98a2b3; }.privacy-note { color: #98a2b3; font-size: 10px; }
.chat-panel { min-width: 0; display: flex; flex-direction: column; background: #fff; }.chat-scroll { min-height: 0; flex: 1; overflow-y: auto; padding: 38px clamp(20px, 5vw, 68px) 22px; }.chat-scroll.is-empty { display: grid; place-items: center; }.welcome-state { width: min(580px, 100%); text-align: center; }.welcome-mark { width: 58px; height: 58px; display: grid; place-items: center; margin: 0 auto 18px; color: #f79009; background: #fff4e5; border-radius: 18px; font-size: 25px; }.welcome-state h2 { margin: 0; color: #101828; font-size: 24px; font-weight: 750; }.welcome-state > p { margin: 9px 0 26px; color: #667085; font-size: 13px; }.prompt-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; text-align: left; }.prompt-card { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 62px; padding: 12px 14px; color: #344054; background: #f8fafc; border: 1px solid #eaecf0; border-radius: 10px; font: inherit; font-size: 12px; text-align: left; cursor: pointer; transition: border-color .2s, background .2s, transform .2s; }.prompt-card:hover { border-color: #9dc2ff; background: #f4f8ff; transform: translateY(-1px); }.prompt-card :deep(.anticon) { flex: 0 0 auto; color: #1677ff; }.message-list { display: flex; flex-direction: column; gap: 30px; }.message-group { display: flex; flex-direction: column; gap: 14px; }.message-row { display: flex; align-items: flex-start; gap: 10px; }.user-row { justify-content: flex-end; }.message-avatar { width: 30px; height: 30px; display: grid; flex: 0 0 30px; place-items: center; border-radius: 9px; font-size: 14px; }.user-avatar { order: 2; color: #fff; background: #667085; }.assistant-message-avatar { color: #fff; background: #172b4d; }.message-bubble { max-width: min(680px, 84%); padding: 13px 16px; border-radius: 14px; font-size: 14px; line-height: 1.7; white-space: pre-wrap; }.user-bubble { color: #fff; background: #1677ff; border-bottom-right-radius: 4px; }.assistant-bubble { color: #344054; background: #f2f4f7; border-bottom-left-radius: 4px; }.assistant-message-wrap { min-width: 0; }.loading-bubble { display: inline-flex; gap: 5px; padding: 17px 18px; }.loading-bubble span { width: 6px; height: 6px; border-radius: 50%; background: #98a2b3; animation: pulse 1.1s ease-in-out infinite; }.loading-bubble span:nth-child(2) { animation-delay: .15s; }.loading-bubble span:nth-child(3) { animation-delay: .3s; }.error-bubble { color: #b42318; background: #fef3f2; }.message-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin-top: 8px; color: #98a2b3; font-size: 11px; }.message-meta :deep(.ant-tag) { margin: 0; font-size: 10px; }.evidence-button { height: auto; padding: 0; color: #1677ff; font-size: 11px; }.evidence-panel { max-width: min(680px, 100%); margin-top: 10px; padding: 16px; background: #fbfcfe; border: 1px solid #e4e7ec; border-radius: 12px; }.evidence-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 14px; color: #344054; font-size: 12px; font-weight: 700; }.evidence-heading small { color: #98a2b3; font-size: 10px; font-weight: 400; }.evidence-block + .evidence-block { margin-top: 14px; }.evidence-label { margin-bottom: 7px; color: #98a2b3; font-size: 10px; font-weight: 700; letter-spacing: .5px; }.evidence-tags { display: flex; flex-wrap: wrap; gap: 6px; }.evidence-tags :deep(.ant-tag) { margin: 0; font-size: 10px; }.reference-list { display: flex; flex-direction: column; gap: 6px; }.reference-item { display: flex; align-items: center; gap: 7px; color: #475467; font-size: 11px; }.reference-index { display: inline-grid; width: 20px; height: 20px; place-items: center; color: #1677ff; background: #eaf2ff; border-radius: 5px; font-size: 10px; }
.draft-action { display: flex; align-items: center; justify-content: space-between; gap: 14px; max-width: min(680px, 100%); margin-top: 10px; padding: 12px 14px; background: #f0f7ff; border: 1px solid #b7d5ff; border-radius: 10px; }.draft-action-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; color: #344054; font-size: 12px; }.draft-action-copy span { color: #667085; font-size: 11px; }.draft-action .ant-btn { flex: 0 0 auto; }
.composer-wrap { padding: 16px clamp(20px, 5vw, 68px) 18px; border-top: 1px solid #eaecf0; }.composer-hint, .composer-foot { display: flex; justify-content: space-between; gap: 12px; color: #98a2b3; font-size: 10px; }.composer-hint { margin-bottom: 7px; }.composer-hint :deep(.anticon) { margin-right: 4px; color: #12b76a; }.composer { display: flex; align-items: flex-end; gap: 10px; padding: 8px 8px 8px 14px; border: 1px solid #d0d5dd; border-radius: 13px; box-shadow: 0 2px 7px rgb(16 24 40 / 4%); transition: border-color .2s, box-shadow .2s; }.composer:focus-within { border-color: #84adff; box-shadow: 0 0 0 3px rgb(22 119 255 / 10%); }.composer :deep(.ant-input) { resize: none; border: 0; box-shadow: none; padding: 4px 0; }.composer :deep(.ant-input:focus) { box-shadow: none; }.composer .ant-btn { width: 38px; height: 38px; flex: 0 0 38px; }.composer-foot { margin-top: 8px; }.composer-foot :deep(.anticon) { margin-right: 3px; }
@keyframes pulse { 0%, 80%, 100% { opacity: .35; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }
@keyframes cursor-blink { to { visibility: hidden; } }
.stream-cursor { display: inline-block; width: 7px; height: 15px; margin-left: 3px; background: #1677ff; vertical-align: text-bottom; animation: cursor-blink 1s steps(2, start) infinite; }
.degraded-hint { color: #b54708; font-size: 10px; }
/* AI 回复的 Markdown 渲染结果；内容已由 dompurify 清洗，这里只负责排版 */
.markdown-body { white-space: normal; overflow-wrap: anywhere; }
.markdown-body > :first-child { margin-top: 0; }
.markdown-body > :last-child { margin-bottom: 0; }
.markdown-body p { margin: 0 0 8px; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4, .markdown-body h5, .markdown-body h6 { margin: 12px 0 6px; font-size: 15px; font-weight: 750; }
.markdown-body ul, .markdown-body ol { margin: 0 0 8px; padding-left: 20px; }
.markdown-body li { margin: 2px 0; }
.markdown-body strong { font-weight: 750; }
.markdown-body a { color: #1677ff; }
.markdown-body code { padding: 1px 5px; background: #f2f4f7; border-radius: 5px; font-size: 12px; }
.markdown-body pre { margin: 0 0 8px; padding: 10px 12px; overflow-x: auto; background: #f8fafc; border: 1px solid #eaecf0; border-radius: 8px; }
.markdown-body pre code { padding: 0; background: none; }
.markdown-body blockquote { margin: 0 0 8px; padding: 2px 12px; color: #475467; border-left: 3px solid #d0d5dd; }
.markdown-body table { width: 100%; margin: 0 0 8px; border-collapse: collapse; font-size: 12px; }
.markdown-body th, .markdown-body td { padding: 5px 8px; border: 1px solid #eaecf0; text-align: left; }
.markdown-body hr { margin: 10px 0; border: 0; border-top: 1px solid #eaecf0; }
.assistant-layout { height: min(760px, calc(100vh - 190px)); min-height: 620px; }
.chat-panel { min-height: 0; }
.chat-scroll { overflow-y: scroll; overscroll-behavior: contain; scrollbar-gutter: stable; scrollbar-width: thin; scrollbar-color: #b7c0cf #f2f4f7; }
.chat-scroll::-webkit-scrollbar { width: 9px; }
.chat-scroll::-webkit-scrollbar-track { background: #f2f4f7; border-radius: 9px; }
.chat-scroll::-webkit-scrollbar-thumb { background: #b7c0cf; border: 2px solid #f2f4f7; border-radius: 9px; }
.composer :deep(.ant-input) { overflow-x: hidden; overflow-y: auto; overflow-wrap: anywhere; }
@media (max-width: 820px) { .assistant-layout { grid-template-columns: 1fr; }.assistant-sidebar { display: none; }.chat-scroll { min-height: 560px; }.assistant-header { align-items: flex-start; }.prompt-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .login-panel { padding: 40px 22px; }.heading-line h1 { font-size: 21px; }.assistant-heading p { max-width: 210px; font-size: 11px; }.new-chat-button { padding-inline: 10px; font-size: 12px; }.chat-scroll { padding: 26px 16px 18px; }.composer-wrap { padding-inline: 16px; }.message-bubble { max-width: 90%; font-size: 13px; }.composer-foot span:last-child { display: none; }.evidence-heading small { display: none; }.draft-action { align-items: flex-start; flex-direction: column; }.draft-action .ant-btn { width: 100%; } }
</style>
