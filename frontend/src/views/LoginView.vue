<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api'
const username = ref('admin'),
  password = ref(''),
  busy = ref(false),
  router = useRouter()
async function submit() {
  if (!username.value || !password.value) return
  busy.value = true
  try {
    await login(username.value, password.value)
    await router.push('/')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="login-brand">
        T
        <span>.</span>
        <small>天商 · 商务云</small>
      </div>
      <div class="login-story-content">
        <div class="eyebrow">PROJECTS. PRICING. CLARITY.</div>
        <h1>
          从一份报价，
          <br />
          看清每个项目。
        </h1>
        <p>
          连接客户、供应商与项目经营。
          <br />
          让成本有依据，让利润看得见。
        </p>
        <div class="login-lines">
          <span>01 项目与客户</span>
          <span>02 报价与成本</span>
          <span>03 利润与回款</span>
        </div>
      </div>
      <div class="login-bottom">快乐猴 / 小象项目经营工作台</div>
    </section>
    <section class="login-form-wrap">
      <form class="login-form" @submit.prevent="submit">
        <div class="eyebrow">WELCOME BACK</div>
        <h2>登录工作空间</h2>
        <p>使用您的企业账户，继续今天的工作。</p>
        <label for="username">用户名</label>
        <el-input id="username" v-model="username" size="large" autocomplete="username" placeholder="请输入用户名" />
        <label for="password">密码</label>
        <el-input
          id="password"
          v-model="password"
          size="large"
          type="password"
          autocomplete="current-password"
          show-password
          placeholder="请输入账户密码"
        />
        <el-button native-type="submit" type="primary" size="large" :loading="busy" class="login-submit">
          登录工作空间
          <span>→</span>
        </el-button>
        <small>账号由管理员分配，首次登录后请修改密码。</small>
      </form>
      <div class="login-copyright">TIANSHANG COMMERCE · 项目经营管理系统</div>
    </section>
  </div>
</template>
