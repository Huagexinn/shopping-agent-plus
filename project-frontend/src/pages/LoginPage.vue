<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AppLayout from '@/layouts/AppLayout.vue'
import { login } from '@/api/auth'
import { setToken } from '@/utils/storage'

const router = useRouter()
const loading = ref(false)
const form = reactive({ identifier: '', password: '' })

async function submitLogin() {
  loading.value = true
  try {
    const result = await login(form.identifier, form.password)
    if (result.code !== 0 || !result.data) {
      throw new Error(result.message)
    }
    setToken(result.data.token)
    message.success('登录成功')
    await router.push('/cart')
  } catch (error) {
    const text = error instanceof Error ? error.message : '登录失败'
    message.error(text)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AppLayout>
    <a-card title="登录" :bordered="false" class="login-card">
      <a-form layout="vertical" @submit.prevent="submitLogin">
        <a-form-item label="用户名或邮箱" required>
          <a-input v-model:value="form.identifier" autocomplete="username" placeholder="请输入用户名或邮箱" />
        </a-form-item>
        <a-form-item label="密码" required>
          <a-input-password v-model:value="form.password" autocomplete="current-password" placeholder="请输入密码" />
        </a-form-item>
        <a-button type="primary" html-type="submit" :loading="loading" block>登录</a-button>
      </a-form>
      <p class="register-link">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
    </a-card>
  </AppLayout>
</template>

<style scoped>
.login-card {
  width: min(420px, 100%);
  margin: 0 auto;
  border-radius: 14px;
}
.register-link { margin: 18px 0 0; color: #667085; text-align: center; font-size: 13px; }.register-link a { color: #1677ff; }
</style>
