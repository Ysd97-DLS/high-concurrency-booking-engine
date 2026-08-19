import { ref } from 'vue'
import { clinicApi } from '@/api/client'

/**
 * 坑 ② 的上半部分：把本地时钟校准到服务端。
 *
 * 支付倒计时不能用浏览器的 `Date.now()` 直接算 —— 用户的系统时间可能偏几分钟甚至几小时
 * （虚拟机、手动改过时区的机器很常见）。偏快会让还能付的单显示「已超时」，用户直接放弃；
 * 偏慢会让早该释放的单一直倒计时，点了支付才发现失败。
 *
 * 做法是 SNTP 的简化版：记录请求前后的本地时间，减掉半个 RTT，得到偏移量。
 *
 * ## 两处后来补上的东西
 *
 * **① 多次取样，取 RTT 最小的那次。** 单次取样把网络抖动直接算进了偏移里：
 * 减掉「半个 RTT」的前提是**上行和下行耗时对称**，而抖动恰恰意味着不对称。
 * RTT 最小的那次样本的不对称程度也最小 —— 这是 NTP 用了几十年的办法，
 * 成本只是多两个几十字节的请求。
 *
 * **② 失败要重试。** 原来第一次失败就 `skewMs = 0`、`synced = false`，然后**永不重试**。
 * 页面加载时一次瞬时网络抖动，就让整个会话的倒计时退化成本地时钟 ——
 * 而这套校准存在的全部意义就是不信本地时钟。
 * 界面上那句「未能校准服务端时间」是诚实的，但诚实地一直坏着不如自己恢复。
 */
export function useServerClock() {
  const skewMs = ref(0)
  const synced = ref(false)
  /** RTT，用来向用户/开发者解释这次校准有多可信 */
  const rttMs = ref(0)

  /** 取样次数。3 次足够滤掉偶发抖动，再多收益很小而首屏延迟可感。 */
  const SAMPLES = 3
  /** 失败后的重试次数与退避基数 */
  const RETRIES = 2
  const RETRY_BASE_MS = 300

  async function sampleOnce() {
    const t0 = Date.now()
    const r = await clinicApi.serverTime()
    const t1 = Date.now()
    if (typeof r?.epochMs !== 'number') {
      throw new Error('server-time 返回里没有 epochMs')
    }
    const rtt = t1 - t0
    // 减半个 RTT：假设上下行对称。取 RTT 最小的样本就是为了让这个假设最接近成立。
    return { skew: r.epochMs - (t0 + rtt / 2), rtt }
  }

  async function sync() {
    for (let attempt = 0; attempt <= RETRIES; attempt++) {
      try {
        let best = null
        for (let i = 0; i < SAMPLES; i++) {
          const s = await sampleOnce()
          if (best === null || s.rtt < best.rtt) {
            best = s
          }
        }
        skewMs.value = best.skew
        rttMs.value = best.rtt
        synced.value = true
        return true
      } catch (e) {
        if (attempt === RETRIES) {
          // 用尽重试才降级。降级是安全的（按本地时钟走），但要让界面能说出来。
          skewMs.value = 0
          rttMs.value = 0
          synced.value = false
          return false
        }
        // 指数退避 + 抖动。和抢号重试同一个道理：所有客户端同步重试会形成惊群。
        const wait = RETRY_BASE_MS * 2 ** attempt + Math.random() * 200
        await new Promise((res) => setTimeout(res, wait))
      }
    }
    return false
  }

  /**
   * 校准后的"现在"。
   *
   * 用 `Date.now() + skew` 而不是 `performance.now()`：后者是单调时钟，
   * 不受系统时间跳变影响，听起来更稳 —— 但笔记本休眠唤醒时它的行为各浏览器不一致，
   * 而休眠期间**真实时间确实流逝了**，`Date.now()` 反而是对的。
   * 会话中途 NTP 步进校正会让这里偏几秒，相对 10 分钟的支付窗口可以接受。
   */
  const now = () => Date.now() + skewMs.value

  return { skewMs, rttMs, synced, sync, now }
}
