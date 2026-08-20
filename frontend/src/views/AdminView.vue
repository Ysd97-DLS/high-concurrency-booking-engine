<script setup>
/**
 * 运营看板。
 *
 * 设计取向和患者端刻意不同 —— 这一点值得单独说，因为它是信息设计的核心判断：
 *
 *   患者端是「读一段流程」：引导性强、一次只做一件事、每步都有明确的下一步。
 *   运营端是「一眼扫状态」：先给结论、异常项高亮、自动刷新、危险操作二次确认。
 *
 * 用同一套组件硬套两边都不好用。所以这里的 KPI 卡片用**语义色**（好/警告/危险），
 * 而不是全部同色 —— 运营需要在 0.5 秒内看出哪里不对，而不是逐个读数字。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, controlApi } from '@/api/client'
// KPI 的装配与「什么算异常」的阈值判定都在 domain/kpi.js，配了 35 个测试。
// 留在组件里的时候它测不到，而它判错的表现是**看板把异常显示成正常**。
import { buildKpis, fmt } from '@/domain/kpi'
import { Refresh } from '@element-plus/icons-vue'

const dash = ref(null)
const schedules = ref([])
const riskEvents = ref([])
const blocked = ref([])
const audit = ref([])
const reconcileLog = ref([])
const reconcileBusy = ref(false)
const reconcileLogFailed = ref(false)
const lastTick = ref('')
const alive = ref(true)
const cfgDraft = ref({})
// 看板聚焦在哪个排班上。**不能不选**：后端默认 1001 是压测号池，
// 不传的话号池余量和放号进度描述的是一个运营根本不管的对象。
const focusId = ref(null)

let timer = null
let busy = false          // 防止轮询和手动刷新叠加

/** 参数说明：运营看到的是键名，不加解释没人知道该往哪调 */
const CFG_DESC = {
  'limit.qps': '放行速率，控制面会自动调',
  'stock.buckets': '活跃桶数，越多热点越散',
  'stock.segment': '号段大小，调大省 Redis',
  'stock.tail': '剩余低于此值进入单件模式',
  'stock.segmentEnabled': '1=启用号段  0=强制单件',
  'riskcontrol.threshold': '风控频次阈值，越低越严',
  'riskcontrol.slowLaneQps': '降权流量的绝对速率',
  'release.spreadSeconds': '分批放号总时长，0=一次放完'
}

const STATUS_LABEL = {
  PENDING_PAY: '待支付', BOOKED: '已预约', EXPIRED: '已失效',
  REFUNDED: '已退号', COMPLETED: '已就诊', NO_SHOW: '已失约'
}

async function refresh() {
  if (busy) return
  busy = true
  try {
    const d = await adminApi.dashboard(focusId.value ?? undefined)
    dash.value = d
    audit.value = d.recentChanges || []
    // 只在用户没有正在编辑时覆盖草稿，否则会把他刚输入的值冲掉
    for (const [k, v] of Object.entries(d.config || {})) {
      if (cfgDraft.value[k] === undefined) cfgDraft.value[k] = v
    }
    // 次级面板用 allSettled 而不是 all。
    //
    // **一个次级面板挂掉不能把整个控制台带下去。**用 Promise.all 时，任意一个
    // 请求失败就会跳到外层 catch，于是 alive=false、整屏显示「连接失败」，
    // 而 KPI、热配置这些核心信息其实是好的。
    //
    // 这个坑我差点自己造出来：对账留档查的是 t_reconcile_log，一张**新加的迁移表**。
    // 忘跑 sql/05 的环境上这个请求必然 500，然后整个看板就打不开了 ——
    // 一个可选的自愈功能没配好，把主界面搞挂，这是很糟的失败模式。
    const [s, r, b, rl] = await Promise.allSettled([
      adminApi.schedules(), adminApi.riskEvents(12), adminApi.blockedPatients(),
      adminApi.reconcileHistory(12)
    ])
    const ok = (x, dflt) => (x.status === 'fulfilled' && x.value != null ? x.value : dflt)
    schedules.value = ok(s, [])
    // 首次加载时把焦点落到一个**真实**排班上，而不是让后端回退到压测池 1001。
    // 优先挑正在放号的（运营此刻最关心），否则挑号最多的那个。
    if (focusId.value == null && schedules.value.length) {
      const real = schedules.value.filter((x) => x.scheduleId < 9000 || x.scheduleId >= 20000)
      const pick = real.find((x) => x.status === 'OPEN') || real[0] || schedules.value[0]
      if (pick) {
        focusId.value = pick.scheduleId
        // 焦点变了，本轮的 dashboard 是按旧焦点取的，下一轮（3 秒后）自然刷新
      }
    }
    riskEvents.value = ok(r, [])
    const bv = ok(b, [])
    blocked.value = Array.isArray(bv) ? bv : [bv]
    const rlv = ok(rl, [])
    reconcileLog.value = Array.isArray(rlv) ? rlv : []
    reconcileLogFailed.value = rl.status === 'rejected'
    lastTick.value = new Date().toLocaleTimeString('zh-CN')
    alive.value = true
  } catch (e) {
    // 走到这里说明 dashboard 本身挂了 —— 那才是真的连不上
    alive.value = false
  } finally {
    busy = false
  }
}

async function applyCfg(key) {
  const r = await controlApi.setConfig(key, cfgDraft.value[key], '运营端手动调整')
  // 护栏可能钳制或驳回 —— 绝不能假设改成功了，必须把实际处置回显出来。
  //
  // 字段名是 `note`（后端 GuardRail.GuardResult 的字段）。这里踩过一个坑：
  // 最初写成 r.guardNote，是我以为的名字而不是后端真实的名字，结果
  //   · 驳回时永远显示"未知原因"（运营不知道是冷却期还是不在白名单）
  //   · 钳制时说明被丢掉（运营填 300 实际生效 20，界面不解释为什么）
  // 而 accepted / appliedValue 恰好都对，所以界面看着完全正常 ——
  // **一个只让"解释"消失、不让"功能"报错的字段名错误，肉眼极难发现。**
  if (r.accepted === false) {
    ElMessage.error(`被护栏驳回：${r.note || '未知原因'}`)
  } else {
    const applied = r.appliedValue ?? cfgDraft.value[key]
    // note 正常放行时是 "ok"，没有信息量，不必展示给运营
    const extra = r.note && r.note !== 'ok' ? `（${r.note}）` : ''
    ElMessage.success(`已生效：${key} = ${applied}${extra}`)
  }
  delete cfgDraft.value[key]      // 清草稿，让下次刷新拿服务端的权威值
  refresh()
}

async function openSch(id) {
  const r = await adminApi.openSchedule(id)
  ElMessage.success(r.message || '已开始放号')
  refresh()
}

async function closeSch(id) {
  try {
    await ElMessageBox.confirm(
      `确认停止排班 ${id} 的放号？剩余号将不再放出，需要重新开启才能继续。`,
      '停止放号', { type: 'warning', confirmButtonText: '确认停止', cancelButtonText: '取消' }
    )
  } catch { return }
  const r = await adminApi.closeSchedule(id)
  ElMessage.success(r.message || '已停止放号')
  refresh()
}

/**
 * 跑一次对账。
 *
 * 预演（dryRun）和真执行走同一个函数，区别只有一个参数和一次二次确认。
 * **真执行必须二次确认**，因为它会改动号源账目——这是这个界面上唯一会
 * 直接改数据而不是改配置的按钮，和「停止放号」「解除拉黑」不是一个量级。
 */
async function runReconcile(dryRun) {
  if (!dryRun) {
    try {
      await ElMessageBox.confirm(
        '真执行会把差额号源补回号池，直接改动账目。建议先预演确认它要做什么。',
        '确认执行对账补偿',
        { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    } catch { return }
  }
  reconcileBusy.value = true
  try {
    const r = await adminApi.reconcileRun(dryRun)
    // acted 才表示真的改了号源。dryRun 时它永远是 false，
    // 所以不能用 acted 判断「调用成功」——那是两件事。
    const kind = r.acted ? 'success' : 'info'
    ElMessage({ type: kind, message: r.decision, duration: 6000 })
  } catch (e) {
    ElMessage.error('对账调用失败：' + (e?.message || e))
  } finally {
    reconcileBusy.value = false
    refresh()
  }
}

async function unblock(id) {
  try {
    await ElMessageBox.confirm(
      `确认解除患者 ${id} 的预约限制？会同时清零失约次数。`,
      '解除拉黑', { type: 'warning' }
    )
  } catch { return }
  const r = await adminApi.unblock(id)
  ElMessage.success(r.message || '已解除')
  refresh()
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div>
    <div class="hd">
      <div>
        <h2 class="page-title">运营看板</h2>
        <p class="page-desc" style="margin: 0">
          数据每 3 秒自动刷新。所有参数变更都经过护栏（白名单 / 区间钳制 / 冷却期）并留审计。
        </p>
      </div>
      <div class="live">
        <el-select v-model="focusId" size="small" style="width: 210px" placeholder="选择排班"
                   @change="refresh">
          <el-option
            v-for="s in schedules" :key="s.scheduleId"
            :label="`[${s.scheduleId}] ${s.doctorName || ''} ${s.visitDate || ''}`"
            :value="s.scheduleId" />
        </el-select>
        <el-tag :type="alive ? 'success' : 'danger'" size="small" effect="dark">
          {{ alive ? '已连接' : '连接失败' }}
        </el-tag>
        <span class="muted">{{ lastTick ? '更新于 ' + lastTick : '加载中…' }}</span>
        <el-button size="small" :icon="Refresh" @click="refresh">立即刷新</el-button>
      </div>
    </div>

    <!-- 部署形态告警。放在最顶上、概览之前，因为它比任何一个 KPI 都严重：
         dedupe.mode=LOCAL 配多实例时，号源会静默蒸发，而下面所有 KPI 都会显示正常。
         实测双实例、共用 2000 个 userId 时没了 20% 的号源，患者全部收到「抢号成功」。
         这个告警在启动日志里也有一份，但日志是出事后才翻的，看板是平时就在看的。 -->
    <el-alert v-if="dash?.deploy?.dedupeUnsafe" type="error" :closable="false" show-icon
              style="margin-bottom: 14px"
              title="判重模式与部署形态不匹配 —— 号源可能正在静默丢失">
      <template #default>
        <div style="line-height: 1.7">
          当前 <b>{{ dash.deploy.instanceCount }}</b> 个实例在跑（{{ dash.deploy.instances.join('、') }}），
          而判重模式是 <b>{{ dash.deploy.dedupeMode }}</b>。
          LOCAL 把「一人一号」判在单个进程的内存里，只有网关按 userId 一致性哈希做粘性路由时才成立。
          <br>
          没有粘性路由的话，同一个患者会在每个实例上各抢到一个号，落库时撞唯一索引 ——
          号从 Redis 扣走却既不落单也不归还。<b>患者收到的是「抢号成功」，只有一致性等式能看见。</b>
          <br>
          处置：给网关配 userId 一致性哈希，或把 <code>flashpilot.dedupe.mode</code> 改成 REDIS 后重启。
        </div>
      </template>
    </el-alert>

    <!-- 概览：先给结论 -->
    <div class="kpis">
      <div v-for="c in buildKpis(dash)" :key="c.k" class="kpi" :class="c.t">
        <div class="k">{{ c.k }}</div>
        <div class="v tabular">{{ c.v }}</div>
        <div class="s">{{ c.s }}</div>
      </div>
    </div>

    <el-row :gutter="16" style="margin-top: 18px">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="box">
          <template #header>热配置（改动会过护栏）</template>
          <div v-for="(v, k) in (dash?.config || {})" :key="k" class="cfgrow">
            <div class="cfgk">
              <code>{{ k }}</code>
              <div class="muted">{{ CFG_DESC[k] || '' }}</div>
            </div>
            <el-input v-model="cfgDraft[k]" size="small" style="width: 96px" />
            <el-button size="small" type="primary" plain @click="applyCfg(k)">应用</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="box">
          <template #header>预约状态分布</template>
          <el-table :data="dash?.appointments || []" size="small" empty-text="暂无预约">
            <el-table-column label="状态" width="110">
              <template #default="{ row }">{{ STATUS_LABEL[row.status] || row.status }}</template>
            </el-table-column>
            <el-table-column prop="count" label="数量" width="90" align="right" class-name="tabular" />
            <el-table-column label="占比">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.round(row.count / (dash.appointments.reduce((s, a) => s + a.count, 0) || 1) * 100)"
                  :stroke-width="8" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="box" style="margin-top: 16px">
      <template #header>排班与放号</template>
      <el-table :data="schedules" size="small" empty-text="暂无排班">
        <el-table-column prop="scheduleId" label="ID" width="80" align="right" class-name="tabular" />
        <el-table-column prop="doctorName" label="医生" width="100" />
        <el-table-column prop="visitDate" label="日期" width="110" />
        <el-table-column label="时段" width="70">
          <template #default="{ row }">{{ row.period === 'AM' ? '上午' : '下午' }}</template>
        </el-table-column>
        <el-table-column prop="slotType" label="号别" width="90" />
        <el-table-column prop="totalSlots" label="总号" width="90" align="right" class-name="tabular" />
        <el-table-column label="已放号" min-width="150">
          <template #default="{ row }">
            <el-progress
              :percentage="row.totalSlots ? Math.round(row.releasedSlots / row.totalSlots * 100) : 0"
              :stroke-width="8" :format="() => fmt(row.releasedSlots)" />
          </template>
        </el-table-column>
        <el-table-column prop="bookedSlots" label="已约" width="90" align="right" class-name="tabular" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small"
              :type="row.status === 'OPEN' ? 'success' : (row.status === 'CLOSED' ? 'danger' : 'info')">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" size="small" type="primary" plain @click="openSch(row.scheduleId)">
              开始放号
            </el-button>
            <el-button v-else-if="row.status === 'OPEN'" size="small" type="danger" plain @click="closeSch(row.scheduleId)">
              停止放号
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="box">
          <template #header>风控命中（最近）</template>
          <el-table :data="riskEvents" size="small" empty-text="暂无风控命中 —— 这是好事">
            <el-table-column label="层级" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.level === 'L1' ? 'warning' : 'danger'" effect="light">
                  {{ row.level }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处置" width="70">
              <template #default="{ row }">{{ row.action === 'DEMOTE' ? '降权' : (row.action === 'BLOCK' ? '拉黑' : row.action) }}</template>
            </el-table-column>
            <el-table-column prop="patientId" label="患者" width="90" align="right" class-name="tabular" />
            <el-table-column prop="deviceId" label="设备" min-width="120" show-overflow-tooltip />
            <el-table-column label="时间" width="90">
              <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(11, 19) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="box">
          <template #header>失约黑名单</template>
          <el-table :data="blocked" size="small" empty-text="黑名单为空">
            <el-table-column prop="id" label="患者" width="90" align="right" class-name="tabular" />
            <el-table-column prop="name" label="姓名" width="110" />
            <el-table-column prop="noShowCount" label="失约" width="70" align="right" class-name="tabular" />
            <el-table-column label="解除时间" min-width="140">
              <template #default="{ row }">{{ (row.blockedUntil || '').replace('T', ' ').slice(0, 16) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button size="small" @click="unblock(row.id)">解除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!--
      对账补偿面板。
      这是界面上唯一会**直接改动号源账目**的功能，所以呈现方式和别处刻意不同：
        · 开关状态放在最显眼处 —— 运营首先要知道「它现在会不会自己动手」
        · 预演按钮在前、执行按钮在后且是危险色 + 二次确认
        · 留档里「拒绝处置」的记录和「已补偿」一样重要，都要显示理由
    -->
    <el-card shadow="never" class="box" style="margin-top: 16px">
      <template #header>
        <div style="display: flex; align-items: center; gap: 10px">
          <span>对账补偿</span>
          <el-tag v-if="dash?.reconcile?.enabled" type="warning" size="small" effect="dark">
            自动补偿已开启
          </el-tag>
          <el-tag v-else type="info" size="small" effect="plain">
            自动补偿已关闭（只观察，不动手）
          </el-tag>
          <span v-if="dash?.reconcile" class="muted" style="font-size: 12px">
            覆盖 {{ dash.reconcile.trackedPools ?? 0 }} 个号池 · 残差已连续 {{ dash.reconcile.consecutiveSame }} 次
          </span>
          <div style="margin-left: auto; display: flex; gap: 8px">
            <el-button size="small" :loading="reconcileBusy" @click="runReconcile(true)">
              预演
            </el-button>
            <el-button size="small" type="danger" plain :loading="reconcileBusy"
                       @click="runReconcile(false)">
              执行
            </el-button>
          </div>
        </div>
      </template>

      <p class="hint">
        校验器发现号源残差后由它补回号池。四道闸门：只补少卖不补超卖、采样不稳定不判定、
        连续 3 次同一残差才动手、单次不超过 100 个。
        <b>「拒绝处置」的记录和「已补偿」一样重要</b>——它说明自动化在什么时候选择了不动手。
      </p>

      <el-alert v-if="reconcileLogFailed" type="warning" :closable="false" show-icon
                style="margin-bottom: 12px"
                title="读不到对账留档"
                description="通常是没跑 sql/05-reconcile-log.sql。看板其余部分不受影响。" />

      <el-table :data="reconcileLog" size="small" empty-text="暂无对账动作（账目一直平衡）">
        <el-table-column prop="poolId" label="号池" width="90" align="right" class-name="tabular" />
        <el-table-column label="残差" width="90" align="right">
          <template #default="{ row }">
            <span class="tabular" :class="row.vanished > 0 ? 'warnv' : (row.vanished < 0 ? 'badv' : '')">
              {{ row.vanished > 0 ? '+' + row.vanished : row.vanished }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="处置" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="row.acted ? 'success' : 'info'">
              {{ row.acted ? '已补偿 ' + row.compensated : '未动手' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="decision" label="判断理由" min-width="300" show-overflow-tooltip />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ (row.createdAt || '').slice(5, 19) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="box" style="margin-top: 16px">
      <template #header>变更审计</template>
      <el-table :data="audit" size="small" empty-text="暂无变更">
        <el-table-column prop="version" label="版本" width="80" align="right" class-name="tabular" />
        <el-table-column prop="param" label="参数" width="200" />
        <el-table-column label="变化" width="150" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.oldValue ?? '—' }} → <b>{{ row.newValue ?? '—' }}</b></span>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="100" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.accepted ? 'success' : 'danger'" effect="light">
              {{ row.accepted ? '生效' : '驳回' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reason || row.guardNote || '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ (row.createdAt || '').slice(5, 19) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.hd { display: flex; align-items: flex-start; gap: 18px; flex-wrap: wrap; margin-bottom: 16px; }
.live { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.box { border: 1px solid var(--fp-line); }

.kpis {
  display: grid; gap: 1px; background: var(--fp-line);
  border: 1px solid var(--fp-line); border-radius: 6px; overflow: hidden;
  grid-template-columns: repeat(auto-fit, minmax(152px, 1fr));
}
.kpi { background: #fff; padding: 12px 14px; }
.kpi .k { font-size: 11.5px; letter-spacing: .1em; color: var(--fp-ink3); }
.kpi .v { font-size: 22px; font-weight: 700; line-height: 1.2; margin-top: 4px; }
.kpi .s { font-size: 12px; color: var(--fp-ink3); margin-top: 2px; line-height: 1.45; }
.kpi.ok .v { color: var(--fp-ok); }
.kpi.warn .v { color: var(--fp-warn); }
.kpi.bad .v { color: var(--fp-bad); }

.cfgrow {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 0; border-bottom: 1px solid var(--fp-line);
}
.cfgrow:last-child { border-bottom: 0; }
.cfgk { flex: 1; min-width: 0; }
.cfgk code { font-size: 12.5px; color: var(--fp-ink2); }
.hint { font-size: 12.5px; color: var(--fp-ink3); margin: 0 0 12px; line-height: 1.6; }
.warnv { color: var(--fp-warn); font-weight: 600; }
.badv { color: var(--fp-bad); font-weight: 700; }
</style>
