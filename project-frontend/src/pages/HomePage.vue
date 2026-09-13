<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ShoppingCartOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { fetchProductDetail, fetchProducts } from '@/api/products'
import { addCartItem } from '@/api/cart'
import { getToken } from '@/utils/storage'
import type { ProductResponse } from '@/types/api'

const products = ref<ProductResponse[]>([])
const total = ref(0)
const loading = ref(false)
const detail = ref<ProductResponse | null>(null)
const detailLoading = ref(false)
const page = ref(1)
const pageSize = 8
const isLoggedIn = computed(() => Boolean(getToken()))

function price(value: number | string) { return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function productTone(id: number) { return ['gold', 'blue', 'green', 'rose'][id % 4] }
async function loadProducts() {
  loading.value = true
  try {
    const params = { page: page.value, size: pageSize, sortBy: 'createdAt' as const, sortDirection: 'desc' as const }
    const result = await fetchProducts(params)
    if (result.code !== 0 || !result.data) throw new Error(result.message)
    products.value = result.data.records; total.value = result.data.total
  } catch (error) { message.error(error instanceof Error ? error.message : '商品加载失败') }
  finally { loading.value = false }
}
async function openDetail(product: ProductResponse) {
  detailLoading.value = true
  try { const result = await fetchProductDetail(product.id); if (result.code !== 0 || !result.data) throw new Error(result.message); detail.value = result.data }
  catch (error) { message.error(error instanceof Error ? error.message : '详情加载失败') }
  finally { detailLoading.value = false }
}
async function addToCart(product: ProductResponse) {
  if (!isLoggedIn.value) { message.info('请先登录后再加入购物车'); return }
  try { const result = await addCartItem(product.id, 1); if (result.code !== 0) throw new Error(result.message); message.success(`${product.title} 已加入购物车`) }
  catch (error) { message.error(error instanceof Error ? error.message : '加入购物车失败') }
}
async function addDetailToCart() { if (detail.value) { await addToCart(detail.value); detail.value = null } }
onMounted(loadProducts)
</script>

<template>
  <AppLayout>
    <section class="hero-section"><div><p class="eyebrow">SMART SHOPPING · 2026</p><h1>找到适合你的<br /><span>下一部手机</span></h1><p class="hero-copy">从热门旗舰到高性价比机型，实时查看价格与库存。</p></div><div class="hero-device"><div class="device-screen"><ThunderboltOutlined /><strong>NUMA</strong><small>smart choice</small></div></div></section>
    <section class="catalog-section"><div class="section-heading"><div><p class="eyebrow dark">CURATED FOR YOU</p><h2>精选商品</h2></div><span class="result-count">共 {{ total }} 件商品</span></div>
      <a-spin :spinning="loading"><div class="product-grid"><a-card v-for="product in products" :key="product.id" class="product-card" :bordered="false" hoverable @click="openDetail(product)"><div class="product-visual" :class="productTone(product.id)"><div class="phone-shape"><div></div></div><span class="stock-tag">{{ product.stock > 0 ? '现货' : '缺货' }}</span></div><div class="product-meta"><span class="brand-label">{{ product.brand }}</span><h3>{{ product.title }}</h3><p>{{ product.model }}</p><div class="product-bottom"><strong>¥{{ price(product.price) }}</strong><a-button type="primary" shape="circle" :disabled="product.stock <= 0" @click.stop="addToCart(product)"><ShoppingCartOutlined /></a-button></div></div></a-card></div></a-spin>
      <a-empty v-if="!loading && products.length === 0" description="暂无商品" /><a-pagination v-if="total > pageSize" v-model:current="page" :page-size="pageSize" :total="total" show-less-items class="pagination" @change="loadProducts" />
    </section>
    <a-modal :open="Boolean(detail)" :footer="null" width="560px" title="商品详情" @cancel="detail = null"><a-spin :spinning="detailLoading"><div v-if="detail" class="detail-layout"><div class="detail-visual product-visual blue"><div class="phone-shape"><div></div></div></div><div class="detail-copy"><span class="brand-label">{{ detail.brand }}</span><h2>{{ detail.title }}</h2><p>{{ detail.description || '暂无商品描述' }}</p><div class="detail-price">¥{{ price(detail.price) }}</div><div class="detail-stock">库存 {{ detail.stock }} 件</div><a-button type="primary" size="large" block :disabled="detail.stock <= 0" @click="addDetailToCart"><ShoppingCartOutlined /> 加入购物车</a-button></div></div></a-spin></a-modal>
  </AppLayout>
</template>

<style scoped>
.hero-section { min-height: 285px; display: flex; align-items: center; justify-content: space-between; padding: 38px 68px; color: #fff; background: #172b4d; border-radius: 4px; overflow: hidden; }.eyebrow { margin: 0 0 12px; color: #fdb022; font-size: 11px; font-weight: 700; letter-spacing: 2px; }.eyebrow.dark { color: #667085; }h1 { margin: 0; font-size: clamp(34px, 5vw, 54px); line-height: 1.12; letter-spacing: 0; font-weight: 800; }h1 span { color: #fdb022; }.hero-copy { margin-top: 18px; color: #c9d3e1; font-size: 15px; }.hero-device { width: 200px; height: 250px; display: grid; place-items: center; transform: rotate(10deg); }.device-screen { width: 136px; height: 236px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; border: 7px solid #344b6c; border-radius: 23px; color: #fdb022; background: #0c1b32; box-shadow: 12px 20px 0 #0e223f; }.device-screen strong { color: #fff; font-size: 16px; letter-spacing: 2px; }.device-screen small { color: #9aa8bd; font-size: 9px; }.catalog-section { padding-top: 42px; }.section-heading { display: flex; align-items: end; justify-content: space-between; margin-bottom: 18px; }h2 { margin: 0; color: #101828; font-size: 28px; font-weight: 750; }.result-count { color: #98a2b3; font-size: 13px; }.product-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; min-height: 280px; }.product-card { overflow: hidden; border-radius: 3px; }.product-card :deep(.ant-card-body) { padding: 0; }.product-visual { position: relative; height: 190px; display: grid; place-items: center; }.product-visual.gold { background: #f8e9bf; }.product-visual.blue { background: #dce9f8; }.product-visual.green { background: #dcefe8; }.product-visual.rose { background: #f5dfe0; }.phone-shape { width: 78px; height: 142px; padding: 6px; border: 4px solid #253858; border-radius: 14px; background: #536b91; box-shadow: 9px 10px 0 rgb(16 24 40 / 12%); }.phone-shape div { height: 100%; border-radius: 8px; background: #f4f6f8; }.stock-tag { position: absolute; top: 12px; left: 12px; padding: 3px 7px; color: #067647; background: #ecfdf3; font-size: 11px; }.product-meta { padding: 16px; }.brand-label { color: #667085; font-size: 12px; }.product-meta h3 { margin: 5px 0 0; color: #101828; font-size: 16px; font-weight: 650; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }.product-meta p { margin: 3px 0 14px; color: #98a2b3; font-size: 12px; }.product-bottom { display: flex; align-items: center; justify-content: space-between; }.product-bottom strong { color: #101828; font-size: 18px; }.pagination { margin-top: 32px; text-align: center; }.detail-layout { display: grid; grid-template-columns: 210px 1fr; gap: 28px; align-items: center; }.detail-visual { height: 270px; }.detail-copy h2 { margin: 6px 0 12px; }.detail-copy p { color: #667085; line-height: 1.7; }.detail-price { margin-top: 20px; color: #d92d20; font-size: 28px; font-weight: 750; }.detail-stock { margin: 8px 0 20px; color: #667085; font-size: 13px; }.pagination { margin-top: 32px; }
@media (max-width: 920px) { .product-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }.hero-section { padding: 34px; }.hero-device { transform: scale(.8) rotate(10deg); } }@media (max-width: 680px) { .hero-section { min-height: 250px; padding: 28px 24px; }.hero-device { display: none; }.product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.product-visual { height: 150px; }.phone-shape { transform: scale(.8); }.detail-layout { grid-template-columns: 1fr; }.detail-visual { height: 180px; } }
</style>
