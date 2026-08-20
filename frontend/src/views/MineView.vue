<script setup>
import { ref, inject, onMounted, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clinicApi } from '@/api/client'
import { statusMeta } from '@/domain/apptStatus'
import { useServerClock } from '@/composables/useServerClock'
import { useCountdown } from '@/composables/useCountdown'
import { TITLE_TEXT, TITLE_TYPE, SLOT_TEXT, PERIOD_TEXT, label } from '@/constants/clinic'
import { Refresh } from '@element-plus/icons-vue'

const patientId = inject('patientId')
const list = ref([])
const loading = ref(false)

// 坑 ②：先校准服务端时间，再用它驱动倒计时
const clock = useServerClock()
const cd = useCountdown(clock.now)

/**
 * 坑 ④ 状态机在 UI 上的表达。
 *
 * 六种状态各自允许的操作完全不同，不能所有行都显示同一组按钮。
 * 把这张表集中定义，而不是在模板里写一串 v-if —— 状态机的规则应该
 * 看得见、改得动，散在模板里就没人知道完整规则是什么了。
 */
// 六状态到界面的映射在 domain/apptStatus.js，配了 19 个测试。
// 要钉住的是 action 字段：它决定页面上出现什么按钮，
// 判错的后果是给用户一个点了必然报错的按钮。
const meta = statusMeta

async function load() {
  loading.value = true
  try {
    list.value = await clinicApi.myAppointments(patientId.value, 30)
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/**
 * 待支付单的截止时间集合（**epoch 毫秒**），用于检测「刚刚过期」。
 *
 * 用 payDeadlineMs 而不是 payDeadline：后者是不带时区的字符串，
 * 会被按客户端时区误读（详见 useCountdown.remaining 的注释）。
 * 这里如果漏改，过期检测会永远不触发 —— 而它的作用正是「倒计时归零后刷新列表，
 * 让服务端的真实状态覆盖前端推算」，静默失效的话用户会一直看着一个已经作废的单。
 */
const pendingDeadlines = computed(() =>
  list.value.filter((a) => a.status === 'PENDING_PAY').map((a) => a.payDeadlineMs)
)

async function onPay(row) {
  const r = await clinicApi.pay(row.apptNo)
  if (r.ok) {
    ElMessage.success('支付成功，预约已生效')
  } else {
    // WRONG_STATE 最常见的成因是：支付的同一刻被超时任务抢先释放了。
    // 后端用带旧状态条件的 UPDATE 让数据库裁决胜负，所以这里不该简单报「失败」，
    // 而要刷新列表让用户看到真实状态。
    ElMessage.warning(r.message || '当前状态不允许支付')
  }
  load()
}

async function onRefund(row) {
  try {
    await ElMessageBox.confirm(
      `确认退掉 ${row.apptNo} 吗？号源会立即释放回号池，其他患者可以抢到。`,
      '退号确认',
      { type: 'warning', confirmButtonText: '确认退号', cancelButtonText: '不退了' }
    )
  } catch {
    return       // 用户取消
  }
  const r = await clinicApi.refund(row.apptNo)
  if (r.ok) {
    ElMessage.success('退号成功，号源已归还')
  } else {
    ElMessage.warning(r.message || '当前状态不允许退号')
  }
  load()
}

onMounted(async () => {
  await clock.sync()          // 必须先校准，否则第一次渲染的倒计时是错的
  cd.start()
  // 倒计时归零 → 刷新列表，让服务端的真实状态覆盖前端的推算
  cd.onExpired(() => setTimeout(load, 1500))
  await load()
})

// tick 时检查有没有单子刚过期
watch(cd.nowMs, () => cd.checkExpired(pendingDeadlines.value))
watch(patientId, load)
</script>

<template>
  <div>
    <h2 class="page-title">我的预约</h2>
    <p class="page-desc">
      待支付的号会在 10 分钟后自动释放回号池。
      <el-tag v-if="clock.synced.value" size="small" type="success" effect="plain">
        已校准服务端时间（偏移 {{ Math.round(clock.skewMs.value) }} ms）
      </el-tag>
      <el-tag v-else size="small" type="warning" effect="plain">
        未能校准服务端时间，倒计时按本地时钟显示
      </el-tag>
    </p>

    <el-card shadow="never" style="border: 1px solid var(--fp-line)">
      <template #header>
        <div style="display: flex; align-items: center">
          <span>预约记录</span>
          <el-button :icon="Refresh" size="small" style="margin-left: auto" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" empty-text="还没有预约记录" style="width: 100%">
        <el-table-column prop="apptNo" label="预约号" width="130" />

        <!--
          科室 / 医生列。这一列是后补的：最初「我的预约」只有预约号、日期、费用、状态，
          患者根本看不出这条记录是哪位医生 —— 而这恰恰是他最需要确认的信息。
          根因在后端：t_appointment 只存 doctor_id，不冗余姓名，
          接口的 toView 也就没有 doctorName 可返回（已补上，见 ScheduleRepository#viewByIds）。
        -->
        <el-table-column label="科室 / 医生" min-width="190">
          <template #default="{ row }">
            <div class="doc-line">
              <span class="doc-name">{{ row.doctorName || `医生 ${row.doctorId}` }}</span>
              <el-tag v-if="row.doctorTitle" :type="TITLE_TYPE[row.doctorTitle]" size="small" effect="plain">
                {{ label(TITLE_TEXT, row.doctorTitle) }}
              </el-tag>
            </div>
            <div class="note">
              {{ row.departmentName || '—' }}
              <template v-if="row.period">· {{ label(PERIOD_TEXT, row.period) }}</template>
              <template v-if="row.slotType">· {{ label(SLOT_TEXT, row.slotType) }}</template>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="visitDate" label="就诊日期" width="115" />
        <el-table-column label="就诊时间" width="95">
          <template #default="{ row }">{{ row.visitTime ? row.visitTime.slice(0, 5) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="seqNo" label="序号" width="70" align="right" class-name="tabular" />
        <el-table-column label="挂号费" width="95" align="right">
          <template #default="{ row }"><span class="tabular">¥{{ row.feeYuan.toFixed(2) }}</span></template>
        </el-table-column>

        <el-table-column label="状态" min-width="180">
          <template #default="{ row }">
            <el-tag :type="meta(row.status).type" size="small" effect="light">
              {{ meta(row.status).label }}
            </el-tag>
            <!-- 坑 ②：倒计时用校准后的时间算 -->
            <div v-if="row.status === 'PENDING_PAY' && row.payDeadlineMs"
                 class="cd tabular" :class="{ urgent: cd.urgent(row.payDeadlineMs) }">
              {{ cd.format(row.payDeadlineMs) }}
            </div>
            <div v-else-if="meta(row.status).note" class="note">{{ meta(row.status).note }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button v-if="meta(row.status).action === 'pay'" type="primary" size="small" @click="onPay(row)">
              支付
            </el-button>
            <el-button v-else-if="meta(row.status).action === 'refund'" type="danger" plain size="small" @click="onRefund(row)">
              退号
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.cd { font-size: 13px; font-weight: 700; color: var(--fp-warn); margin-top: 3px; }
.cd.urgent { color: var(--fp-bad); }
.note { font-size: 12px; color: var(--fp-ink3); margin-top: 3px; line-height: 1.45; }
.doc-line { display: flex; align-items: center; gap: 6px; }
.doc-name { font-weight: 600; }
</style>
