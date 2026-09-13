<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import {
  clearCart,
  fetchCart,
  removeCartItem,
  updateCartItem,
} from '@/api/cart'
import { getToken } from '@/utils/storage'
import type { CartResponse } from '@/types/api'

const cart = ref<CartResponse | null>(null)
const loading = ref(false)
const activeProductId = ref<number | null>(null)
const editQuantities = reactive<Record<number, number>>({})
const router = useRouter()

const isLoggedIn = computed(() => Boolean(getToken()))

function syncEditQuantities(value: CartResponse) {
  Object.keys(editQuantities).forEach((key) => delete editQuantities[Number(key)])
  value.items.forEach((item) => {
    editQuantities[item.productId] = item.quantity
  })
}

async function loadCart() {
  if (!isLoggedIn.value) {
    cart.value = null
    return
  }
  loading.value = true
  try {
    const result = await fetchCart()
    if (result.code !== 0 || !result.data) {
      throw new Error(result.message)
    }
    cart.value = result.data
    syncEditQuantities(result.data)
  } catch (error) {
    const text = error instanceof Error ? error.message : '购物车暂不可用'
    message.error(`加载购物车失败：${text}`)
  } finally {
    loading.value = false
  }
}

async function submitUpdate(productId: number, originalQuantity: number) {
  const quantity = editQuantities[productId] ?? originalQuantity
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 999) {
    editQuantities[productId] = originalQuantity
    message.error('数量必须是1到999之间的整数')
    return
  }
  activeProductId.value = productId
  try {
    const result = await updateCartItem(productId, quantity)
    if (result.code !== 0 || !result.data) {
      throw new Error(result.message)
    }
    cart.value = result.data
    syncEditQuantities(result.data)
    message.success('购物车数量已更新')
  } catch (error) {
    editQuantities[productId] = originalQuantity
    const text = error instanceof Error ? error.message : '数量更新失败'
    message.error(text)
  } finally {
    activeProductId.value = null
  }
}

async function submitRemove(productId: number) {
  activeProductId.value = productId
  try {
    const result = await removeCartItem(productId)
    if (result.code !== 0 || !result.data) {
      throw new Error(result.message)
    }
    cart.value = result.data
    syncEditQuantities(result.data)
    message.success('商品已从购物车移除')
  } catch (error) {
    const text = error instanceof Error ? error.message : '删除购物车商品失败'
    message.error(text)
  } finally {
    activeProductId.value = null
  }
}

function confirmClear() {
  Modal.confirm({
    title: '确认清空购物车？',
    content: '清空后需要重新添加商品。',
    okText: '确认清空',
    cancelText: '取消',
    onOk: async () => {
      try {
        const result = await clearCart()
        if (result.code !== 0 || !result.data) {
          throw new Error(result.message)
        }
        cart.value = result.data
        syncEditQuantities(result.data)
        message.success('购物车已清空')
      } catch (error) {
        const text = error instanceof Error ? error.message : '清空购物车失败'
        message.error(text)
        throw error
      }
    },
  })
}

onMounted(loadCart)
</script>

<template>
  <AppLayout>
    <a-card title="我的购物车" :bordered="false" class="cart-card">
      <template #extra>
        <a-space>
          <a-button @click="router.push('/')">继续挑选商品</a-button>
          <a-button v-if="cart?.items.length" danger @click="confirmClear">清空购物车</a-button>
        </a-space>
      </template>

      <a-alert
        v-if="!isLoggedIn"
        type="warning"
        show-icon
        message="请先登录"
        description="购物车接口需要 Bearer JWT；登录后重新打开此页面即可读取当前购物车。"
      />
      <a-button v-if="!isLoggedIn" type="primary" class="login-button" @click="router.push('/login')">
        去登录
      </a-button>

      <a-spin v-else :spinning="loading">
        <a-empty v-if="cart && cart.items.length === 0" description="购物车还是空的">
          <template #extra>
            <a-space>
              <a-button type="primary" @click="router.push('/')">去挑选商品</a-button>
              <a-button @click="router.push('/assistant')">让 AI 助手帮我加购</a-button>
            </a-space>
          </template>
        </a-empty>
        <a-list v-else-if="cart" :data-source="cart.items" item-layout="horizontal">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta :title="item.title" :description="`${item.brand} ${item.model}`" />
              <div class="item-price">¥{{ item.displayPrice }}</div>
              <a-input-number
                v-model:value="editQuantities[item.productId]"
                :min="1"
                :max="999"
                :disabled="activeProductId === item.productId"
              />
              <span class="stock">库存 {{ item.stock }}</span>
              <a-button
                type="link"
                :loading="activeProductId === item.productId"
                @click="submitUpdate(item.productId, item.quantity)"
              >
                更新
              </a-button>
              <a-button
                danger
                type="link"
                :loading="activeProductId === item.productId"
                @click="submitRemove(item.productId)"
              >
                删除
              </a-button>
              <div class="item-subtotal">小计 ¥{{ item.displaySubtotal }}</div>
            </a-list-item>
          </template>
        </a-list>

        <a-descriptions v-if="cart" class="cart-summary" :column="2" bordered size="small">
          <a-descriptions-item label="商品总件数">{{ cart.totalQuantity }}</a-descriptions-item>
          <a-descriptions-item label="展示总金额">¥{{ cart.displayTotalAmount }}</a-descriptions-item>
        </a-descriptions>
      </a-spin>
    </a-card>
  </AppLayout>
</template>

<style scoped>
.cart-card {
  max-width: 1100px;
  margin: 0 auto;
  border-radius: 14px;
}

.item-price,
.item-subtotal {
  margin-left: 18px;
  white-space: nowrap;
}

.item-subtotal {
  min-width: 120px;
  text-align: right;
}

.stock {
  margin-left: 12px;
  color: #86909c;
  white-space: nowrap;
}

.cart-summary {
  margin-top: 24px;
}

.login-button {
  margin-top: 16px;
}
</style>
