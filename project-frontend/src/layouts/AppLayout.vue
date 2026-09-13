<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ShoppingCartOutlined, UserOutlined, LogoutOutlined, MedicineBoxOutlined, RobotOutlined } from '@ant-design/icons-vue'
import { clearToken, getToken } from '@/utils/storage'
import { fetchCurrentUser } from '@/api/auth'
import type { UserProfileResponse } from '@/types/api'

const router = useRouter()
const user = ref<UserProfileResponse | null>(null)
const isLoggedIn = ref(Boolean(getToken()))

async function loadUser() {
  if (!isLoggedIn.value) return
  try {
    const result = await fetchCurrentUser()
    if (result.code === 0) user.value = result.data
  } catch { clearToken() }
}
function logout() { clearToken(); isLoggedIn.value = false; user.value = null; message.success('已退出登录'); router.push('/') }
onMounted(loadUser)
</script>

<template>
  <a-layout class="app-shell">
    <a-layout-header class="app-header"><div class="header-inner">
      <RouterLink class="brand" to="/"><span class="brand-mark"><MedicineBoxOutlined /></span><span>NUMA</span></RouterLink>
      <nav class="main-nav"><RouterLink to="/" class="nav-link">精选手机</RouterLink><RouterLink to="/assistant" class="nav-link">AI助手</RouterLink><RouterLink to="/orders" class="nav-link">我的订单</RouterLink></nav>
      <div class="header-actions"><RouterLink to="/assistant" class="icon-link" title="AI助手"><RobotOutlined /></RouterLink><RouterLink to="/cart" class="icon-link" title="购物车"><ShoppingCartOutlined /></RouterLink>
        <template v-if="isLoggedIn"><span class="user-name"><UserOutlined /> {{ user?.username || '已登录' }}</span><a-button type="text" class="logout-button" @click="logout"><LogoutOutlined /> 退出</a-button></template>
        <RouterLink v-else to="/login" class="login-link">登录</RouterLink>
      </div>
    </div></a-layout-header>
    <a-layout-content class="app-content"><slot /></a-layout-content>
    <a-layout-footer class="app-footer">NUMA 数码商城 · 让选择更简单</a-layout-footer>
  </a-layout>
</template>

<style scoped>
.app-shell { min-height: 100vh; background: #f7f8fa; }.app-header { height: 72px; padding: 0 28px; background: #101828; }.header-inner { width: min(1180px, 100%); height: 100%; margin: 0 auto; display: flex; align-items: center; gap: 38px; }.brand { display: inline-flex; align-items: center; gap: 9px; color: #fff; font-size: 20px; font-weight: 800; letter-spacing: 2px; }.brand-mark { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; color: #101828; background: #fdb022; font-size: 18px; }.main-nav { display: flex; gap: 26px; }.nav-link, .login-link, .icon-link { color: #c9d3e1; transition: color .2s; }.nav-link:hover, .nav-link.router-link-active, .login-link:hover, .icon-link:hover { color: #fdb022; }.header-actions { display: flex; align-items: center; gap: 18px; margin-left: auto; }.icon-link { font-size: 21px; line-height: 1; }.user-name { display: inline-flex; align-items: center; gap: 6px; color: #e5eaf1; font-size: 13px; }.logout-button { color: #c9d3e1; padding: 0; }.logout-button:hover { color: #fdb022 !important; }.app-content { width: min(1180px, calc(100% - 40px)); margin: 0 auto; padding: 34px 0 56px; }.app-footer { padding: 20px; color: #98a2b3; text-align: center; background: transparent; font-size: 12px; }
@media (max-width: 680px) { .app-header { padding: 0 16px; }.header-inner { gap: 18px; }.main-nav { display: none; }.user-name { display: none; }.app-content { width: min(100% - 24px, 1180px); padding-top: 22px; } }
</style>
