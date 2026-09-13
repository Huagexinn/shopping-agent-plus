<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RouterLink } from 'vue-router'
import { message } from 'ant-design-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { register } from '@/api/auth'

const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', email: '', password: '', confirmPassword: '' })
async function submit() {
  if (form.password !== form.confirmPassword) { message.error('两次输入的密码不一致'); return }
  loading.value = true
  try { const result = await register(form.username, form.email, form.password); if (result.code !== 0) throw new Error(result.message); message.success('注册成功，请登录'); await router.push('/login') }
  catch (error) { message.error(error instanceof Error ? error.message : '注册失败') }
  finally { loading.value = false }
}
</script>
<template>
  <AppLayout><a-card class="auth-card" :bordered="false"><div class="auth-heading"><p class="eyebrow">JOIN NUMA</p><h1>创建账号</h1><p>注册后即可同步购物车与订单。</p></div><a-form layout="vertical" @submit.prevent="submit"><a-form-item label="用户名" required><a-input v-model:value="form.username" autocomplete="username" placeholder="3-50位字母、数字、下划线或短横线" /></a-form-item><a-form-item label="邮箱" required><a-input v-model:value="form.email" autocomplete="email" placeholder="you@example.com" /></a-form-item><a-form-item label="密码" required><a-input-password v-model:value="form.password" autocomplete="new-password" placeholder="至少8位" /></a-form-item><a-form-item label="确认密码" required><a-input-password v-model:value="form.confirmPassword" autocomplete="new-password" placeholder="再次输入密码" /></a-form-item><a-button type="primary" html-type="submit" block size="large" :loading="loading">注册</a-button></a-form><p class="switch-link">已有账号？<RouterLink to="/login">去登录</RouterLink></p></a-card></AppLayout>
</template>
<style scoped>
.auth-card { width: min(440px, 100%); margin: 28px auto; border-radius: 4px; }.auth-heading { margin-bottom: 26px; }.eyebrow { margin: 0 0 8px; color: #667085; font-size: 11px; font-weight: 700; letter-spacing: 2px; }.auth-heading h1 { margin: 0; color: #101828; font-size: 30px; font-weight: 750; }.auth-heading p:last-child { color: #98a2b3; }.switch-link { margin: 20px 0 0; color: #667085; text-align: center; font-size: 13px; }.switch-link a { color: #1677ff; }
</style>
