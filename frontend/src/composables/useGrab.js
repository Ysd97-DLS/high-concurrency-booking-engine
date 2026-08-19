import { ref } from 'vue'
import { grabApi, deviceId, CODE } from '@/api/client'

/**
 * 抢号：防重复提交 + 限流退避重试。—— 坑 ① 和坑 ③
 *
 * 这两个坑放在一起，因为它们互相咬合：不做退避重试，用户就会自己狂点，
 * 于是坑 ① 变得更严重；不做防重复，退避重试又会被并发的点击打乱。
 */
export function useGrab() {
  /**
   * ## 坑 ① 防重复提交
   *
   * 放号瞬间用户一定会狂点按钮。不挡的后果不只是浪费请求：
   *
   * - **浪费自己的限流配额**——主限流是全局共享的，越点越抢不到
   * - **被风控当成黄牛**——频次判据会把狂点的真实患者识别成异常行为
   *
   * 用 Set 而不是单个布尔值：以 poolId 为粒度，
   * 用户可以同时抢不同医生的号，但同一个号池只能有一个在飞的请求。
   *
   * 只置灰按钮是不够的——按住回车、或者页面被脚本驱动时，
   * 按钮的 disabled 状态不参与判断。所以需要这一层数据层面的锁。
   */
  const inFlight = ref(new Set())

  /** 当前重试进度，用来给按钮显示「重试 2/5…」 */
  const retryState = ref({})

  const isGrabbing = (poolId) => inFlight.value.has(poolId)

  /**
   * ## 坑 ③ 429 退避重试
   *
   * 抢号被限流（code 4290）是**正常态**而不是错误——放号瞬间绝大多数请求
   * 都会被限流挡掉。弹一个「系统繁忙」让用户自己再点，等于把重试责任推给用户，
   * 而且会直接引发坑 ①。
   *
   * ### 抖动为什么不能省
   *
   * 没有抖动，所有客户端会在同一时刻同时重试，形成一波又一波的同步冲击
   * （惊群效应），把刚缓下来的服务端再打下去。
   *
   * 这和后端 AIMD 控制律的超调是**同一类问题**：
   * 系统的响应速度远慢于施压的速度，于是形成振荡。
   * 服务端的解法是"看趋势不看水位"，客户端的解法是"随机化重试时刻"，
   * 本质都是给反馈环加阻尼。
   */
  async function grab(poolId, holderId, { maxRetry = 5, onRetry } = {}) {
    if (inFlight.value.has(poolId)) {
      return { code: -1, message: '正在处理中，请勿重复点击' }
    }
    // Set 是引用类型，Vue 的响应式需要重新赋值才能触发更新
    inFlight.value = new Set(inFlight.value).add(poolId)

    try {
      for (let attempt = 0; attempt <= maxRetry; attempt++) {
        const r = await grabApi.grab(poolId, holderId, deviceId())

        // 两种"稍后再试"要分开处理：
        //   4290 限流   —— 系统忙，重试大概率成功，正常退避
        //   4291 风控降权 —— 你的节奏被判定为异常，**继续快速重试只会更严重**
        // 后者退避基数要大得多，否则前端的重试本身就在给风控喂证据。
        const limited = r.code === CODE.RATE_LIMITED
        const demoted = r.code === CODE.RISK_DEMOTED

        // 不是这两种就是终态：成功、售罄、重复、拉黑都直接返回
        if (!limited && !demoted) {
          return r
        }
        if (attempt === maxRetry) {
          return r      // 重试用尽，把最后一次结果返回
        }

        // 指数退避：200ms、400ms、800ms、1600ms… 再叠加 0~200ms 抖动。
        // 抖动不能省：否则所有客户端同步重试形成惊群，
        // 这和服务端 AIMD 要解决的是同一类阻尼问题。
        // 被降权时基数放大到 5 倍，主动把节奏降下来。
        const base = demoted ? 1000 : 200
        const backoff = base * 2 ** attempt + Math.random() * 200
        retryState.value = { ...retryState.value, [poolId]: { attempt: attempt + 1, maxRetry } }
        onRetry?.(attempt + 1, maxRetry, Math.round(backoff))
        await new Promise((res) => setTimeout(res, backoff))
      }
    } finally {
      // finally 里释放：异常路径也必须恢复，否则按钮会永久卡住
      const next = new Set(inFlight.value)
      next.delete(poolId)
      inFlight.value = next
      const rs = { ...retryState.value }
      delete rs[poolId]
      retryState.value = rs
    }
  }

  return { grab, isGrabbing, inFlight, retryState }
}
