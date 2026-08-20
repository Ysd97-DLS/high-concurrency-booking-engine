<script setup>
import { ref, inject, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clinicApi, CODE, CODE_TEXT } from '@/api/client'
import { useGrab } from '@/composables/useGrab'
import { TITLE_TEXT, TITLE_TYPE, SLOT_TEXT } from '@/constants/clinic'
import { localDateOffset, dateOptions } from '@/constants/dates'
import { Refresh } from '@element-plus/icons-vue'

const router = useRouter()
const patientId = inject('patientId')

// 坑 ① + ③ 都封装在这个 composable 里
const { grab, isGrabbing, retryState } = useGrab()

const departments = ref([])
const deptId = ref(null)
// 默认选明天：真实挂号系统普遍提前 7 天放号，今天的号一般已经放完了。
//
// 日期计算全部走 @/constants/dates —— 这里踩过一个只在清晨出现的差一天 bug：
// 原来用 `toISOString()` 取日期，而它返回的是 UTC。UTC+8 的本地 00:00–08:00 之间
// UTC 还停在前一天，整组日期往前偏一天。而**放号就在 6:00 / 7:00**，
// 正好落在坏掉的窗口里；09:00 之后结果又是对的，白天怎么测都测不出来。
const date = ref(localDateOffset(1))
const schedules = ref([])
const loading = ref(false)

/** 可选日期范围：今天起 8 天 */
const dateSelectOptions = computed(() => dateOptions(8))

async function loadDepartments() {
  departments.value = await clinicApi.departments()
  if (departments.value.length && deptId.value == null) {
    deptId.value = departments.value[0].id
  }
}

async function loadSchedules() {
  if (deptId.value == null) return
  loading.value = true
  try {
    schedules.value = await clinicApi.schedules(deptId.value, date.value)
  } catch (e) {
    ElMessage.error('加载排班失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/** 剩余号数。注意用 total - booked，而不是后端直接给「剩余」——
    因为「已放号数」和「已约号数」是两个不同维度，分批放号时前者可能小于总数。 */
const leftOf = (s) => Math.max(0, s.totalSlots - s.bookedSlots)

async function onGrab(s) {
  const r = await grab(s.scheduleId, {
    onRetry: (n, max) => ElMessage.info({ message: `排队中，第 ${n}/${max} 次重试…`, duration: 1200 })
  })
  if (!r) return

  if (r.code === CODE.OK) {
    ElMessage.success(CODE_TEXT[CODE.OK])
    router.push('/mine')          // 抢到就跳去支付，不要让用户自己找
    return
  }
  // 业务分支各自给明确提示。这些都是正常路径，不用 error 级别刷红。
  const text = r.message || CODE_TEXT[r.code] || '抢号失败'
  if (r.code === CODE.SOLD_OUT || r.code === CODE.ALREADY_BOUGHT) {
    ElMessage.warning(text)
  } else if (r.code === CODE.RISK_BLOCKED) {
    ElMessage.error(text)
  } else {
    ElMessage.info(text)
  }
  loadSchedules()                 // 无论成败都刷一下余量
}

onMounted(async () => {
  await loadDepartments()
  await loadSchedules()
})
</script>

<template>
  <div>
    <h2 class="page-title">预约挂号</h2>
    <p class="page-desc">选择科室与就诊日期，抢到号后请在 10 分钟内完成支付，超时号源会自动释放回号池。</p>

    <el-card shadow="never" class="filters">
      <el-form :inline="true">
        <el-form-item label="科室">
          <el-select v-model="deptId" style="width: 140px" @change="loadSchedules">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="就诊日期">
          <el-select v-model="date" style="width: 200px" @change="loadSchedules">
            <el-option v-for="d in dateSelectOptions" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :icon="Refresh" @click="loadSchedules">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-skeleton v-if="loading" :rows="4" animated style="margin-top: 16px" />

    <el-empty v-else-if="!schedules.length" description="这一天该科室暂无排班" />

    <div v-else class="grid">
      <el-card v-for="s in schedules" :key="s.scheduleId" shadow="hover" class="doc">
        <div class="head">
          <span class="name">{{ s.doctorName }}</span>
          <el-tag :type="TITLE_TYPE[s.title] || 'info'" size="small" effect="light">
            {{ TITLE_TEXT[s.title] || s.title }}
          </el-tag>
        </div>

        <div class="meta">
          {{ s.period === 'AM' ? '上午' : '下午' }}
          {{ (s.visitStart || '').slice(0, 5) }}–{{ (s.visitEnd || '').slice(0, 5) }}
          ·
          {{ SLOT_TEXT[s.slotType] || s.slotType }}
        </div>

        <div class="spec">{{ s.specialty || '—' }}</div>

        <el-divider style="margin: 12px 0" />

        <div class="foot">
          <span class="fee tabular">¥{{ (s.feeCents / 100).toFixed(2) }}</span>
          <span class="left tabular">余 {{ leftOf(s) }} / {{ s.totalSlots }}</span>
          <el-button
            type="primary"
            :disabled="leftOf(s) <= 0"
            :loading="isGrabbing(s.scheduleId)"
            @click="onGrab(s)"
          >
            {{
              leftOf(s) <= 0 ? '已满'
              : (retryState[s.scheduleId] ? `重试 ${retryState[s.scheduleId].attempt}…` : '抢号')
            }}
          </el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.filters { border: 1px solid var(--fp-line); }
.filters :deep(.el-form-item) { margin-bottom: 0; }
.grid {
  margin-top: 16px;
  display: grid; gap: 14px;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
}
.doc { border: 1px solid var(--fp-line); }
.head { display: flex; align-items: center; gap: 8px; }
.name { font-size: 16px; font-weight: 600; }
.meta { color: var(--fp-ink3); font-size: 13px; margin-top: 5px; }
.spec { color: var(--fp-ink2); font-size: 13.5px; margin-top: 8px; min-height: 40px; line-height: 1.6; }
.foot { display: flex; align-items: center; gap: 12px; }
.fee { font-weight: 700; color: var(--fp-bad); font-size: 16px; }
.left { color: var(--fp-ink3); font-size: 13px; margin-left: auto; }
</style>
