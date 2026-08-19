import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// 必须在 import 被测模块之前 mock —— vi.mock 会被提升到文件顶部，
// 但 mock 工厂里不能引用外部变量，所以用一个可变对象来控制返回值。
// 建模成「服务端时钟 = 本地时钟 + offsetMs」，而不是一个静态的时间戳。
// 静态时间戳会在 fake timer 推进（比如走重试退避）时凭空产生偏移 ——
// 那测的就不是被测逻辑，而是 mock 自己的缺陷了。
const server = { offsetMs: 0, shouldFail: false, failTimes: 0, calls: 0 }
vi.mock('@/api/client', () => ({
  clinicApi: {
    serverTime: async () => {
      server.calls++
      if (server.shouldFail) throw new Error('network down')
      if (server.failTimes > 0) {
        server.failTimes--
        throw new Error('transient')
      }
      return { epochMs: Date.now() + server.offsetMs, iso: 'mocked' }
    }
  }
}))

const { useServerClock } = await import('./useServerClock')

/**
 * 坑 ② 的上半部分：时钟校准。
 *
 * 这组测试守的是一个**用户完全看不出原因**的故障：本地时钟偏快时，
 * 还能支付的单会显示「已超时」，用户直接放弃，而服务端一切正常、日志里什么都没有。
 * 没有校准逻辑的话，这个 bug 只会以「客服说号莫名其妙没了」的形式出现。
 */
describe('useServerClock', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // 固定本地时间，这样偏移量是确定的
    vi.setSystemTime(new Date('2026-08-17T12:00:00.000Z'))
    server.shouldFail = false
    server.offsetMs = 0
    server.failTimes = 0
    server.calls = 0
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('本地时钟准确时偏移接近 0', async () => {
    server.offsetMs = 0
    const clock = useServerClock()
    await clock.sync()

    expect(clock.synced.value).toBe(true)
    expect(Math.abs(clock.skewMs.value)).toBeLessThan(50)
  })

  it('本地时钟偏快 5 分钟时算出负偏移，now() 被拉回服务端时间', async () => {
    // 用户的机器比服务端快 5 分钟 —— 这正是「还能付的单显示已超时」的成因
    server.offsetMs = -5 * 60_000
    const serverNow = Date.now() + server.offsetMs

    const clock = useServerClock()
    await clock.sync()

    expect(clock.skewMs.value).toBeLessThan(0)
    expect(Math.abs(clock.skewMs.value + 5 * 60_000)).toBeLessThan(50)
    // 校准后的「现在」必须贴近服务端时间，而不是本地时间
    expect(Math.abs(clock.now() - serverNow)).toBeLessThan(50)
  })

  it('本地时钟偏慢时算出正偏移', async () => {
    // 偏慢的后果同样有害：早该释放的单一直在倒计时，用户点了支付才发现失败
    server.offsetMs = 3 * 60_000
    const clock = useServerClock()
    await clock.sync()

    expect(clock.skewMs.value).toBeGreaterThan(0)
    expect(Math.abs(clock.skewMs.value - 3 * 60_000)).toBeLessThan(50)
  })

  /**
   * 偏移量要减掉半个 RTT，因为服务端的时间戳是在请求处理的那一刻生成的，
   * 大约落在往返的中点。不减的话偏移会系统性地偏大一整个 RTT。
   */
  it('偏移量按往返中点计算，不把整个 RTT 算进偏移', async () => {
    server.offsetMs = 500            // 服务端比本地快 500ms

    const clock = useServerClock()
    await clock.sync()

    // 三个样本都是零延迟（mock 立即 resolve），所以中点 = t0，偏移就是纯偏差 500
    expect(Math.abs(clock.skewMs.value - 500)).toBeLessThan(60)
    // RTT 记录下来供界面解释这次校准有多可信
    expect(clock.rttMs.value).toBeLessThan(60)
  })

  /**
   * 多次取样、取 RTT 最小的那次。
   *
   * 「减掉半个 RTT」的前提是上下行耗时**对称**，而网络抖动恰恰意味着不对称。
   * RTT 最小的样本不对称程度也最小 —— 这是 NTP 用了几十年的办法。
   * 单次取样会把那一次的抖动直接算进偏移里。
   */
  it('取样多次，用 RTT 最小的那次算偏移', async () => {
    server.offsetMs = 0
    const clock = useServerClock()
    await clock.sync()
    // 默认 3 次取样
    expect(server.calls).toBe(3)
  })

  /**
   * 降级而不是崩溃 —— 和后端「Agent 挂了退化成纯规则」是同一个思路。
   * 拿不到服务端时间时倒计时可能不准，但页面绝不能因此打不开。
   */
  it('一直失败时退化成本地时钟，并把 synced 标成 false', async () => {
    server.shouldFail = true
    const clock = useServerClock()
    const p = clock.sync()
    await vi.runAllTimersAsync()      // 走完重试退避
    await p

    expect(clock.synced.value).toBe(false)
    expect(clock.skewMs.value).toBe(0)
    expect(Math.abs(clock.now() - Date.now())).toBeLessThan(50)
  })

  it('synced=false 时界面要能据此提示「按本地时钟显示」', async () => {
    server.shouldFail = true
    const clock = useServerClock()
    const p = clock.sync()
    await vi.runAllTimersAsync()
    await p
    // 这个标记是给 UI 用的：不能悄悄用一个可能错的时间去显示倒计时而不告诉用户
    expect(clock.synced.value).toBe(false)
  })

  /**
   * 失败要重试。
   *
   * 原实现第一次失败就 skew=0 / synced=false，然后**永不重试** ——
   * 页面加载时一次瞬时网络抖动，就让整个会话的倒计时退化成本地时钟，
   * 而这套校准存在的全部意义就是不信本地时钟。
   */
  it('瞬时失败后重试成功，不因为一次抖动就整场降级', async () => {
    server.offsetMs = 0
    server.failTimes = 2             // 前两次调用失败
    const clock = useServerClock()
    const p = clock.sync()
    await vi.runAllTimersAsync()
    await p

    expect(clock.synced.value).toBe(true)
    expect(Math.abs(clock.skewMs.value)).toBeLessThan(60)
  })

  it('重试退避是指数增长且带抖动', async () => {
    // 和抢号重试是同一类问题：固定退避一样能"重试成功"，
    // 只是所有客户端会在同一时刻一起冲击服务端。
    const waits = []
    const spy = vi.spyOn(globalThis, 'setTimeout').mockImplementation((fn, ms) => {
      waits.push(ms)
      fn()                            // 立即执行，不真的等
      return 0
    })
    vi.spyOn(Math, 'random').mockReturnValue(0.5)   // 抖动固定成 100ms，便于断言

    server.shouldFail = true
    const clock = useServerClock()
    await clock.sync()
    spy.mockRestore()
    Math.random.mockRestore()

    // RETRIES=2 → 两次退避：300*2^0+100=400、300*2^1+100=700
    expect(waits).toEqual([400, 700])
    expect(clock.synced.value).toBe(false)
  })
})
