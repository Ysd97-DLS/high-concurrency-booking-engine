import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useCountdown } from './useCountdown'

/**
 * 坑 ② 的下半部分：倒计时。
 *
 * 注意这里 `useCountdown` 内部调了 `onUnmounted`，而测试没有组件实例，
 * Vue 会打一行警告。这不影响被测逻辑（倒计时计算全是纯函数），
 * 所以刻意不引入 jsdom + 组件挂载 —— 为了消一行警告去装整个 DOM 环境不值得。
 */
describe('useCountdown', () => {
  const T0 = new Date('2026-08-17T12:00:00.000Z').getTime()
  // 传入固定的 now，等价于「已经校准过的服务端时间」
  let fakeNow

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(T0)
    fakeNow = () => T0
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // 原来这里是 `new Date(T0 + offsetMs).toISOString()` —— 产出带 Z 的 UTC 字符串。
  // 那种字符串 `new Date()` 能正确解析，所以测试一直是绿的，
  // **却恰好绕开了真实的失败模式**：后端发来的是【不带时区】的
  // LocalDateTime 字符串，会被按客户端时区误读。
  // 测试用的数据形状必须和生产一致，否则它守的是一个不存在的场景。
  const at = (offsetMs) => T0 + offsetMs

  it('剩余时长按传入的 now 算，不用 Date.now()', () => {
    // 把本地时钟拨快一小时，结果必须不变 —— 这就是坑 ② 的全部意义
    const cd = useCountdown(fakeNow)
    const before = cd.remaining(at(120_000))
    vi.setSystemTime(T0 + 3600_000)
    expect(cd.remaining(at(120_000))).toBe(before)
    expect(before).toBe(120_000)
  })

  it('格式化成 mm:ss，个位数补零', () => {
    const cd = useCountdown(fakeNow)
    expect(cd.format(at(600_000))).toBe('10:00')
    expect(cd.format(at(65_000))).toBe('01:05')
    expect(cd.format(at(9_000))).toBe('00:09')
  })

  it('已过期显示「已超时」而不是负数时间', () => {
    const cd = useCountdown(fakeNow)
    expect(cd.format(at(-1))).toBe('已超时')
    expect(cd.format(at(-60_000))).toBe('已超时')
    // 恰好归零的那一刻也算超时，不能显示 00:00 让用户以为还能付
    expect(cd.format(at(0))).toBe('已超时')
  })

  it('缺失截止时间时返回 0，不抛异常', () => {
    const cd = useCountdown(fakeNow)
    // 非待支付状态的单没有 payDeadline，模板里同一个函数会被调到
    expect(cd.remaining(null)).toBe(0)
    expect(cd.remaining(undefined)).toBe(0)
    expect(cd.format(null)).toBe('已超时')
  })

  it('剩余不足 1 分钟算紧急，已超时不算', () => {
    const cd = useCountdown(fakeNow)
    expect(cd.urgent(at(60_000))).toBe(true)      // 边界：正好 60 秒
    expect(cd.urgent(at(59_000))).toBe(true)
    expect(cd.urgent(at(61_000))).toBe(false)
    // 已经超时的不该继续标红闪 —— 它需要的是刷新，不是催促
    expect(cd.urgent(at(-1))).toBe(false)
    expect(cd.urgent(at(0))).toBe(false)
  })

  /**
   * 倒计时归零必须触发列表刷新。
   *
   * 前端算出来的「已超时」只是推算，真正的状态转移由后端的 3 秒扫描任务完成。
   * 不刷新的后果：界面一直显示「已超时」而状态还是「待支付」，
   * 用户点支付才发现失败 —— 比直接告诉他更差。
   */
  it('有单子过期时触发回调，让服务端状态覆盖前端推算', () => {
    const cd = useCountdown(fakeNow)
    const cb = vi.fn()
    cd.onExpired(cb)

    expect(cd.checkExpired([at(60_000), at(120_000)])).toBe(false)
    expect(cb).not.toHaveBeenCalled()

    expect(cd.checkExpired([at(60_000), at(-1)])).toBe(true)
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('空列表和空值不触发回调 —— 避免无谓的刷新风暴', () => {
    const cd = useCountdown(fakeNow)
    const cb = vi.fn()
    cd.onExpired(cb)
    expect(cd.checkExpired([])).toBe(false)
    expect(cd.checkExpired([null, undefined])).toBe(false)
    expect(cb).not.toHaveBeenCalled()
  })

  /**
   * 一个全局 ticker 驱动所有行。几十行待支付单如果各自 setInterval，
   * 就是几十个定时器和几十次独立重渲染，而它们做的事完全一样。
   */
  it('start 之后每秒推进一次 nowMs，stop 之后停止', () => {
    let t = T0
    const cd = useCountdown(() => t)
    cd.start()

    t = T0 + 1000
    vi.advanceTimersByTime(1000)
    expect(cd.nowMs.value).toBe(T0 + 1000)

    t = T0 + 2000
    vi.advanceTimersByTime(1000)
    expect(cd.nowMs.value).toBe(T0 + 2000)

    cd.stop()
    t = T0 + 9999
    vi.advanceTimersByTime(5000)
    expect(cd.nowMs.value).toBe(T0 + 2000)   // 停了就不该再动
  })

  it('重复 start 不会叠加定时器', () => {
    let ticks = 0
    const cd = useCountdown(() => { ticks++; return T0 })
    cd.start()
    cd.start()
    cd.start()
    const before = ticks
    vi.advanceTimersByTime(1000)
    // 三次 start 只应产生一次 tick，否则几十行列表会有几十倍开销
    expect(ticks - before).toBe(1)
    cd.stop()
  })

  describe('时区无关性（回归：不能再接受不带时区的字符串）', () => {
    it('接受 epoch 毫秒，结果与运行时区无关', () => {
      // 这是坑② 真正要保证的东西：倒计时只依赖两个 epoch 之差，
      // 而 epoch 没有时区。旧实现用 new Date('2026-08-18T20:30:30') 解析
      // 后端发来的不带时区字符串，客户端时区一变就整体偏移几小时 ——
      // 实测服务端 CST、真实剩余 10 分钟时：UTC 客户端显示 490:00。
      const now = 1_700_000_000_000
      const cd = useCountdown(() => now)
      expect(cd.remaining(now + 600_000)).toBe(600_000)
      expect(cd.format(now + 600_000)).toBe('10:00')
      expect(cd.format(now + 59_000)).toBe('00:59')
      expect(cd.format(now - 1)).toBe('已超时')
    })

    it('传字符串时返回 0 并报错，绝不静默算出一个错误值', () => {
      // 容忍字符串就等于给那个时区 bug 留后门。错得响亮比错得安静好。
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
      const cd = useCountdown(() => 1_700_000_000_000)
      expect(cd.remaining('2026-08-18T20:30:30')).toBe(0)
      expect(spy).toHaveBeenCalled()
      expect(String(spy.mock.calls[0])).toContain('payDeadlineMs')
      spy.mockRestore()
    })

    it('null / undefined 视为 0，不报错（列表里有非待支付单是正常的）', () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
      const cd = useCountdown(() => 1_700_000_000_000)
      expect(cd.remaining(null)).toBe(0)
      expect(cd.remaining(undefined)).toBe(0)
      expect(spy).not.toHaveBeenCalled()
      spy.mockRestore()
    })

    it('urgent 只在最后一分钟内为真，且不含已超时', () => {
      const now = 1_700_000_000_000
      const cd = useCountdown(() => now)
      expect(cd.urgent(now + 61_000)).toBe(false)
      expect(cd.urgent(now + 60_000)).toBe(true)
      expect(cd.urgent(now + 1)).toBe(true)
      expect(cd.urgent(now)).toBe(false)      // 已超时不算"紧急"，它已经没救了
      expect(cd.urgent(now - 5_000)).toBe(false)
    })
  })
})
