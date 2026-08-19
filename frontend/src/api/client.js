/**
 * 统一的接口封装。
 *
 * 一个关键约定要先讲清楚：后端**所有业务错误都走 HTTP 200 + code 字段**，
 * 只有传输层错误（网络断、5xx）才是非 200。
 *
 * 所以前端不能用 HTTP 状态判断业务结果。比如"号已售罄"是 200 + code 4001，
 * 如果按 `res.ok` 判断就会把它当成功；反过来把 4001 做成 HTTP 400，
 * 浏览器控制台会刷一片红色错误，而它其实是完全正常的业务分支。
 *
 * 这个设计不是随意的：秒杀场景里"售罄""限流""重复"都是**高频正常路径**，
 * 不该被当成异常。异常保留给真正意外的情况。
 */

/** 业务码。和后端 SeckillOutcome 一一对应。 */
export const CODE = {
  OK: 200,
  SOLD_OUT: 4001,
  ALREADY_BOUGHT: 4002,
  RISK_BLOCKED: 4030,
  RATE_LIMITED: 4290,
  /**
   * 被风控降权，慢车道也满了。
   *
   * 和 4290 分开是刻意的，尽管两者对用户的意思都是"稍后再试"：
   * 4290 是系统忙（重试大概率会成功），4291 是你的行为被判定为异常
   * （重试还是会被降权，要放慢节奏）。**退避策略应该不一样**，
   * 所以前端必须能区分——见 useGrab 里两者的处理。
   */
  RISK_DEMOTED: 4291,
  INTERNAL_ERROR: 5000
}

/** 业务码 → 给用户看的话。后端也会给 message，这里作为兜底和口径统一。 */
export const CODE_TEXT = {
  [CODE.OK]: '抢号成功，请在 10 分钟内完成支付',
  [CODE.SOLD_OUT]: '号源已满',
  [CODE.ALREADY_BOUGHT]: '您已预约过该医生当天的号',
  [CODE.RISK_BLOCKED]: '您有多次失约记录，暂时无法预约',
  [CODE.RATE_LIMITED]: '当前排队人数过多，请稍后再试',
  [CODE.RISK_DEMOTED]: '您的操作过于频繁，已进入排队通道，请放慢速度重试',
  [CODE.INTERNAL_ERROR]: '系统繁忙，请稍后再试'
}

class HttpError extends Error {
  constructor(status, url) {
    super(`HTTP ${status} ${url}`)
    this.status = status
  }
}

async function request(url, options = {}) {
  const res = await fetch(url, options)
  // 只有传输层错误才抛。业务码由调用方按 code 分支处理。
  if (!res.ok) throw new HttpError(res.status, url)
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

const get = (url) => request(url)
const post = (url) => request(url, { method: 'POST' })

/** 把对象拼成查询串，顺手过滤掉 undefined/null，避免拼出 `?a=undefined` */
function qs(params) {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') p.append(k, String(v))
  }
  return p.toString()
}

// ---------------------------------------------------------------------------
// 患者端
// ---------------------------------------------------------------------------

export const clinicApi = {
  serverTime: () => get('/clinic/server-time'),
  departments: () => get('/clinic/departments'),
  schedules: (departmentId, date) => get(`/clinic/schedules?${qs({ departmentId, date })}`),
  myAppointments: (patientId, limit = 30) => get(`/clinic/appointments?${qs({ patientId, limit })}`),
  detail: (apptNo) => get(`/clinic/appointments/${apptNo}`),
  pay: (apptNo) => post(`/clinic/appointments/${apptNo}/pay`),
  refund: (apptNo) => post(`/clinic/appointments/${apptNo}/refund`),
  complete: (apptNo) => post(`/clinic/appointments/${apptNo}/complete`),
  noShow: (apptNo) => post(`/clinic/appointments/${apptNo}/no-show`)
}

/** 抢号。走引擎热路径，不在 /clinic 下 —— 这条边界就是「引擎」和「业务」的分界。 */
export const grabApi = {
  grab: (poolId, holderId, deviceId) =>
    post(`/seckill/${poolId}?${qs({ holderId, deviceId })}`),
  poolState: (poolId) => get(`/seckill/state/${poolId}`)
}

// ---------------------------------------------------------------------------
// 运营端
// ---------------------------------------------------------------------------

export const adminApi = {
  /**
   * 运营看板。
   *
   * **必须传 scheduleId。**后端默认值是 1001（压测号池），不传就永远显示压测池的
   * 号池余量和放号进度 —— 而运营真正在管的是今天/明天那几个排班。
   * 实测过这个后果：看板显示「号池余量 560、放号 10.7%」，
   * 而当天的五个排班是 50/80/120/40/100 且都已放满，两组数字毫无关系。
   * 界面上每个数字都在动、看着很正常，但描述的是另一个对象。
   */
  dashboard: (scheduleId) => get(`/admin/dashboard?${qs({ scheduleId })}`),
  schedules: () => get('/admin/schedules'),
  createSchedule: (params) => post(`/admin/schedules?${qs(params)}`),
  openSchedule: (id) => post(`/admin/schedules/${id}/open`),
  closeSchedule: (id) => post(`/admin/schedules/${id}/close`),
  progress: (id) => get(`/admin/schedules/${id}/progress`),
  riskEvents: (limit = 15) => get(`/admin/risk/events?${qs({ limit })}`),
  blockedPatients: () => get('/admin/patients/blocked'),
  unblock: (id) => post(`/admin/patients/${id}/unblock`),

  /**
   * 对账补偿。
   *
   * 注意 dryRun 默认 true —— 这个接口会**改动号源账目**，
   * 手滑的代价不对称，所以默认值选「只预演」而不是「真执行」。
   * 后端的 @RequestParam 默认值也是 true，两边一致。
   */
  reconcileRun: (dryRun = true) => post(`/admin/reconcile/run?${qs({ dryRun })}`),
  reconcileHistory: (limit = 20) => get(`/admin/reconcile/history?${qs({ limit })}`)
}

export const controlApi = {
  metrics: () => get('/control/metrics'),
  config: () => get('/control/config'),
  /**
   * 改热参数。
   *
   * 注意响应里的 `accepted` —— 护栏可能钳制到区间边界，也可能因冷却期未过直接驳回。
   * **不要假设改成功了**，必须把 `note`（护栏做了什么）回显给运营，
   * 否则他会以为参数已经生效而实际没有。
   *
   * 字段名有个容易搞混的地方：这个接口的即时响应里叫 `note`
   * （后端 GuardRail.GuardResult），而变更审计表的历史记录里叫 `guardNote`
   * （ConfigAuditRepository.Entry）。同一个含义两个名字，两边都不能写错。
   */
  setConfig: (param, value, reason) =>
    post(`/control/config?${qs({ param, value, reason })}`),
  rollback: () => post('/control/config/rollback'),
  audit: (limit = 20) => get(`/control/audit?${qs({ limit })}`)
}

// ---------------------------------------------------------------------------
// 设备指纹
// ---------------------------------------------------------------------------

/**
 * 设备指纹。风控 L2 判据靠它识别「一机多号」批量代抢。
 *
 * 真实系统会用更完整的指纹（Canvas 渲染差异、字体列表、WebGL 参数），
 * 这里用一个持久化的随机 ID 演示语义。
 *
 * 注意它是**故意**持久化到 localStorage 的：清掉就等于换了台设备，
 * 这既是它的用途，也是它的局限——真黄牛会主动清。所以风控不能只靠它，
 * 而要三层判据一起看。
 */
export function deviceId() {
  const KEY = 'fp-device-id'
  let d = localStorage.getItem(KEY)
  if (!d) {
    d = 'web-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem(KEY, d)
  }
  return d
}
