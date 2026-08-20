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

// ---------------------------------------------------------------------------
// 患者身份
// ---------------------------------------------------------------------------

/**
 * 当前持有的患者令牌。
 *
 * 后端的抢号、支付、退号、我的预约现在都要求 `X-Patient-Token`，
 * 患者身份从**签名令牌**里解出，不再是 `?patientId=` / `?holderId=` 参数。
 *
 * 改动的起因是一次自查里实测到的两件事：
 *   · `POST /seckill/20016?holderId=999999999` —— 编造的 ID 也能抢到号，
 *     而风控的频次判据全部按这个 ID 计数，每次换一个就整套失效
 *   · `POST /clinic/appointments/A20006-37/refund` —— 退掉了别人的号
 *
 * 存在 sessionStorage 而不是 localStorage：关掉标签页就失效，
 * 更接近「会话」的语义。这个演示项目没有真实登录，令牌也没有有效期，
 * 缩短它的存活时间是唯一能做的收敛。
 */
const TOKEN_KEY = 'fp-patient-token'

/**
 * 内存里的令牌副本，也是 sessionStorage 不可用时的唯一存放处。
 *
 * 为什么要容忍 sessionStorage 不可用：访问它并不总是安全的 ——
 * Safari 无痕模式下历史上会直接抛异常，浏览器隐私设置能禁掉它，
 * 服务端渲染时它根本不存在。而这段代码在**每个请求**的路径上，
 * 一次异常就是整个应用的所有接口全挂。
 * 降级的后果只是「刷新页面要重新换令牌」，完全可以接受。
 */
let tokenInMemory = null

function safeStorage() {
  try {
    return typeof sessionStorage !== 'undefined' ? sessionStorage : null
  } catch {
    return null   // 访问 sessionStorage 这个标识符本身就可能抛
  }
}

export function setPatientToken(token) {
  tokenInMemory = token || null
  const s = safeStorage()
  if (!s) return
  try {
    if (token) s.setItem(TOKEN_KEY, token)
    else s.removeItem(TOKEN_KEY)
  } catch { /* 存储配额满或被禁用；内存副本已经写好了 */ }
}

export function getPatientToken() {
  if (tokenInMemory) return tokenInMemory
  const s = safeStorage()
  if (!s) return null
  try {
    tokenInMemory = s.getItem(TOKEN_KEY)
    return tokenInMemory
  } catch {
    return null
  }
}

/**
 * 正在进行中的身份换取。
 *
 * 存在的理由是一个具体的竞态：切换演示患者时，App.vue 的 watch 去换新令牌，
 * MineView 的 watch 去重新加载「我的预约」，两个 watch 都同步触发 ——
 * 而换令牌是异步的。于是加载请求会带着**上一个患者的令牌**发出去，
 * 页面显示的是别人的预约单。
 *
 * 修法不是让每个页面自己去等（那要求每个新页面的作者都记得这件事，
 * 迟早有人忘），而是让所有请求在这一层自动等待身份就绪。
 */
let pendingIdentify = null

/**
 * 换取患者令牌。**这是演示用的登录桩**——真实系统这里必须校验密码或短信验证码。
 *
 * 后端按来源 IP 限流（每分钟 20 次）。限流的是 IP 而不是 patientId，
 * 因为攻击者换的正是 patientId ——「按被换掉的那个字段限流」是这类
 * 限流最常见的设计错误。
 */
export async function identify(patientId) {
  pendingIdentify = (async () => {
    // 注意这里绕过 request()：它会 await pendingIdentify，而现在正在设置它 —— 会死锁。
    const res = await fetch(`/clinic/identify?${qs({ patientId })}`, { method: 'POST' })
    if (!res.ok) throw new HttpError(res.status, '/clinic/identify')
    const r = JSON.parse(await res.text())
    setPatientToken(r.token)
    return r
  })()
  try {
    return await pendingIdentify
  } finally {
    pendingIdentify = null
  }
}

/** 缺少或失效的患者令牌。调用方据此把人送回「登录」而不是提示系统故障。 */
export class UnauthenticatedError extends Error {
  constructor() {
    super('尚未识别患者身份')
    this.status = 401
  }
}

async function request(url, options = {}) {
  // 身份正在换取中就先等它。见 pendingIdentify 上面那段注释里的竞态。
  // 失败也继续往下走 —— 让请求自己收到 401，由调用方按「未登录」处理，
  // 比在这里抛一个来源不明的错误更好定位。
  if (pendingIdentify) {
    try { await pendingIdentify } catch { /* 交给下面的 401 分支 */ }
  }
  const token = getPatientToken()
  const res = await fetch(url, {
    ...options,
    headers: {
      ...(options.headers || {}),
      // 用自定义头而不是 Cookie，顺带天然免疫 CSRF：
      // 浏览器不会自动把它带上，跨站页面也就没法借用户的身份发请求。
      ...(token ? { 'X-Patient-Token': token } : {})
    }
  })
  // 401 单独成一类。它不是「系统故障」而是「你还没登录」，
  // 两者的正确处理相反：前者该重试，后者重试一万次也没用。
  //
  // 把它混进 HttpError 的后果很具体：useGrab 的重试逻辑会对着 401
  // 空转五次退避，用户等了好几秒才看到一个「系统繁忙」。
  if (res.status === 401) {
    setPatientToken(null)   // 令牌已经不被接受了，留着只会让后续请求继续失败
    throw new UnauthenticatedError()
  }
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
  // patientId 参数去掉了：身份来自令牌。
  // 原来那个写法让任何人都能翻出任意患者的全部预约（17 个字段，含凭证号），
  // 而凭证号配上原来无校验的退号接口就是一条完整的攻击链。
  myAppointments: (limit = 30) => get(`/clinic/appointments?${qs({ limit })}`),
  detail: (apptNo) => get(`/clinic/appointments/${apptNo}`),
  pay: (apptNo) => post(`/clinic/appointments/${apptNo}/pay`),
  refund: (apptNo) => post(`/clinic/appointments/${apptNo}/refund`)
  // complete / noShow 移到 adminApi 了 —— 它们是**院方**操作。
  // 留在患者端的后果实测过：no-show 调三次就能把一个真实患者禁约 30 天。
}

/**
 * 抢号。走引擎热路径，不在 /clinic 下 —— 这条边界就是「引擎」和「业务」的分界。
 *
 * holderId 参数没有了：身份由服务端从令牌里解出。这是修复里最关键的一处 ——
 * 风控的三层频次判据、设备阈值、失约黑名单全部以它为计数键，
 * 客户端能自己报的话，每次换一个随机值就让整套风控归零。
 */
export const grabApi = {
  grab: (poolId, deviceId) => post(`/seckill/${poolId}?${qs({ deviceId })}`)
  // poolState 删掉了。它是**声明了但从来没有页面调用**的死代码，
  // 而 /seckill/state 会暴露实例 ID、号段命中率、借调与异常计数 ——
  // 运维视角的内部状态。既然没人用，就随其它诊断接口一起收到 AdminGuard 后面，
  // 需要的时候在本机 curl 即可。
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
  reconcileHistory: (limit = 20) => get(`/admin/reconcile/history?${qs({ limit })}`),

  // 就诊结果登记。原来在 /clinic 下且完全无校验：
  //   · complete 把单子推进 COMPLETED 终态 —— 患者从此退不了号
  //   · noShow 调三次就让 no_show_count 到 3 → 禁约 30 天
  // 它们是院方判断，**患者对自己的单也不该有这两个权限** ——
  // 所以修法不是加所有权校验，而是整个搬到运维面。
  complete: (apptNo) => post(`/admin/appointments/${apptNo}/complete`),
  noShow: (apptNo) => post(`/admin/appointments/${apptNo}/no-show`)
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
