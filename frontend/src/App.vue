<script setup>
/**
 * 应用外壳：顶栏 + 路由出口。
 *
 * 患者 ID 放在这里而不是各页面里，是因为「挂号」和「我的预约」两个页面都要用它，
 * 而它又不值得引入 Pinia —— 项目的共享状态只有这一个，
 * 用 provide/inject 就够了。
 *
 * 什么时候该上状态管理库？当共享状态开始有「派生计算」和「跨页面同步修改」
 * 需求的时候。现在还没到，提前引入只会增加理解成本。
 */
import { ref, provide, inject, watch } from 'vue'
import { useRoute } from 'vue-router'
// 图标要显式 import。unplugin 能自动处理 <el-xxx> 组件，
// 但 @element-plus/icons-vue 的图标是普通组件，需要手动引。
import { QuestionFilled } from '@element-plus/icons-vue'

const route = useRoute()
const elLocale = inject('elLocale')

// 演示用的患者 ID。真实系统这里是登录态（患者身份从 token 里取，前端不可篡改）。
const patientId = ref(Number(localStorage.getItem('fp-patient') || 1001))
watch(patientId, (v) => localStorage.setItem('fp-patient', String(v)))

provide('patientId', patientId)
</script>

<template>
  <el-config-provider :locale="elLocale">
    <div class="shell">
      <header class="topbar">
        <div class="inner">
          <div class="logo">FlashPilot <span>预约挂号</span></div>

          <el-menu :default-active="route.path" mode="horizontal" router :ellipsis="false" class="nav">
            <el-menu-item index="/book">挂号</el-menu-item>
            <el-menu-item index="/mine">我的预约</el-menu-item>
            <el-menu-item index="/admin">运营看板</el-menu-item>
          </el-menu>

          <div class="patient">
            <span class="muted">患者 ID</span>
            <el-input-number v-model="patientId" :min="1" :controls="false" size="small" style="width: 96px" />
            <el-tooltip placement="bottom"
              content="演示用。真实系统这里是登录态，患者身份从 token 里取，前端不可篡改。">
              <el-icon class="hint"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </div>
      </header>

      <main class="body">
        <router-view />
      </main>
    </div>
  </el-config-provider>
</template>

<style scoped>
.shell { min-height: 100vh; }
.topbar {
  background: #fff;
  border-bottom: 1px solid var(--fp-line);
  position: sticky; top: 0; z-index: 100;
}
.inner {
  max-width: 1240px; margin: 0 auto; padding: 0 18px;
  display: flex; align-items: center; gap: 20px; height: 56px;
}
.logo { font-weight: 700; font-size: 17px; white-space: nowrap; }
.logo span { color: var(--fp-brand); }
.nav { border-bottom: 0 !important; flex: 1; }
.patient { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.hint { color: var(--fp-ink3); cursor: help; }
.body { max-width: 1240px; margin: 0 auto; padding: 22px 18px 60px; }
</style>
