import { ref, onUnmounted } from 'vue'

/**
 * 倒计时。—— 坑 ② 的下半部分
 *
 * ## 为什么用一个全局 tick 而不是每行一个 setInterval
 *
 * 「我的预约」列表可能有几十行待支付单。如果每行组件自己 `setInterval(1s)`，
 * 就有几十个定时器同时跑，每个都触发自己的响应式更新和重渲染。
 * 而它们要做的事完全一样——把同一个"现在"减去各自的截止时间。
 *
 * 所以这里只维护**一个** ticker，它每秒更新一次 `nowMs`，
 * 所有需要倒计时的组件都读这一个值。几十行的开销和一行几乎一样。
 *
 * ## 倒计时归零时必须刷新列表
 *
 * 前端算出来的"已超时"只是**推算**。真正的状态转移由后端的 3 秒扫描任务完成
 * （`PENDING_PAY → EXPIRED` 并归还号源）。
 *
 * 所以倒计时到 0 之后要触发一次列表刷新，让服务端的真实状态覆盖前端的猜测。
 * 不刷新的后果：界面一直显示"已超时"但状态还是"待支付"，
 * 用户点支付时才发现失败，体验比直接告诉他更差。
 *
 * 反过来也有可能——用户在最后一秒支付成功了，而前端已经显示"已超时"。
 * 刷新之后状态会变成"已预约"，这是正确的：**服务端才是权威**。
 */
export function useCountdown(nowFn) {
  const nowMs = ref(nowFn())
  let timer = null
  const expiredCallbacks = new Set()

  function start() {
    if (timer) return
    timer = setInterval(() => {
      nowMs.value = nowFn()
    }, 1000)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 组件卸载时一定要清掉定时器，否则路由切走之后它还在跑 */
  onUnmounted(stop)

  /**
   * 算剩余毫秒。
   * @param deadline ISO 时间串（后端返回的 payDeadline）
   */
  /**
   * 距 deadline 还剩多少毫秒。
   *
   * **deadline 必须是 epoch 毫秒（数字），不能是不带时区的时间字符串。**
   *
   * 原实现是 `new Date(deadline).getTime()`，而后端发来的 `payDeadline` 是
   * `LocalDateTime.toString()` 的产物 —— "2026-08-18T20:30:30"，**不带时区**。
   * 按 ECMAScript 规范，这种形式会被当成**客户端本地时间**解析，
   * 而它实际是**服务端**本地时间。实测（服务端 CST，真实剩余 10 分钟）：
   *
   * | 客户端时区 | 倒计时显示 |
   * |---|---|
   * | Asia/Shanghai | 10:00 |
   * | UTC | 490:00 |
   * | America/New_York | 730:00 |
   *
   * 而这**恰好废掉了坑② 的全部意义**：server-time 校准修的是时钟*偏移*，
   * 修不了时区*误读*。患者以为还有几小时，号早就被释放了 ——
   * 正是这套校准本来要防的那个失败。
   *
   * 所以现在只接受后端新增的 `payDeadlineMs`（epoch 毫秒，无歧义）。
   * 传字符串进来会拿到 0 并在控制台报警，而不是静默算出一个错误的倒计时 ——
   * **错得响亮比错得安静好。**
   */
  function remaining(deadlineMs) {
    if (deadlineMs == null) return 0
    if (typeof deadlineMs !== 'number') {
      // 不静默兜底：容忍字符串就等于把那个时区 bug 留了个后门
      console.error(
        '[useCountdown] deadline 必须是 epoch 毫秒，收到了',
        typeof deadlineMs, JSON.stringify(deadlineMs),
        '—— 不带时区的时间字符串会被按客户端时区误读，请用后端的 payDeadlineMs'
      )
      return 0
    }
    return deadlineMs - nowMs.value
  }

  /** 格式化成 mm:ss */
  function format(deadline) {
    const ms = remaining(deadline)
    if (ms <= 0) return '已超时'
    const s = Math.floor(ms / 1000)
    return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
  }

  /** 剩余不足 1 分钟：UI 上要变红提醒 */
  function urgent(deadline) {
    const ms = remaining(deadline)
    return ms > 0 && ms <= 60_000
  }

  function onExpired(cb) {
    expiredCallbacks.add(cb)
  }

  /**
   * 检查是否有单子刚刚过期。调用方在每次 tick 后调它，
   * 有过期的就刷新列表（见上面「倒计时归零时必须刷新列表」）。
   */
  function checkExpired(deadlines) {
    const anyExpired = deadlines.some((d) => d && remaining(d) <= 0)
    if (anyExpired) expiredCallbacks.forEach((cb) => cb())
    return anyExpired
  }

  return { nowMs, start, stop, remaining, format, urgent, onExpired, checkExpired }
}
