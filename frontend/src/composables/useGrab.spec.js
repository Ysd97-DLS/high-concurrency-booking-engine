import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * 被 mock 的后端。用一个队列控制每次 grab 返回什么，
 * 这样能精确构造「连续限流 N 次后成功」这类序列。
 */
const backend = { queue: [], calls: [] }

vi.mock('@/api/client', () => ({
  CODE: {
    OK: 200,
    SOLD_OUT: 4001,
    ALREADY_BOUGHT: 4002,
    RISK_BLOCKED: 4030,
    RATE_LIMITED: 4290,
    RISK_DEMOTED: 4291,
    INTERNAL_ERROR: 5000
  },
  CODE_TEXT: {},
  deviceId: () => 'test-device',
  grabApi: {
    // 签名跟着真实接口变了：holderId 不再由调用方传，
    // 患者身份由服务端从签名令牌里解出（见 client.js 的 X-Patient-Token）。
    grab: async (poolId, dev) => {
      backend.calls.push({ poolId, dev })
      return backend.queue.shift() ?? { code: 200, message: 'ok' }
    }
  }
}))

const { useGrab } = await import('./useGrab')
const { CODE } = await import('@/api/client')

/**
 * 坑 ① 防重复提交 + 坑 ③ 限流退避重试。
 *
 * 这两个坑在同一个 composable 里，因为它们互相咬合：
 * 不做退避重试用户就会自己狂点（坑 ① 更严重），
 * 不做防重复退避重试又会被并发点击打乱。
 */
describe('useGrab', () => {
  beforeEach(() => {
    backend.queue = []
    backend.calls = []
    vi.useRealTimers()
  })

  // ---------------------------------------------------------------- 坑 ①

  it('同一号池的并发调用只放行一个 —— 数据层面的锁，不依赖按钮 disabled', async () => {
    const { grab, isGrabbing } = useGrab()
    backend.queue = [{ code: 200, message: 'ok' }]

    // 不 await 第一个，模拟「第一个请求还在飞的时候又点了一次」
    const first = grab(1001)
    const second = await grab(1001)

    // 第二次必须被本地拦住，而不是发出去
    expect(second.code).toBe(-1)
    expect(second.message).toContain('请勿重复')
    await first
    expect(backend.calls.length).toBe(1)
    expect(isGrabbing(1001)).toBe(false)
  })

  it('不同号池互不影响 —— 用户可以同时抢不同医生的号', async () => {
    const { grab } = useGrab()
    backend.queue = [{ code: 200 }, { code: 200 }]

    const a = grab(1001)
    const b = grab(1002)
    await Promise.all([a, b])

    expect(backend.calls.map((c) => c.poolId).sort()).toEqual([1001, 1002])
  })

  /**
   * 锁必须在 finally 里释放。异常路径不释放的话按钮会**永久卡住**，
   * 而这种 bug 只在后端偶发抛错时出现，本地开发几乎碰不到。
   */
  it('请求抛异常后锁也要释放，否则按钮永久卡死', async () => {
    const { grab, isGrabbing } = useGrab()
    // 只用 spyOn 让它抛。
    //
    // 第一版这里还多写了一行 `backend.queue = [Promise.reject(...)]`，
    // 那个 promise 从未被 await —— 于是变成一个游荡的 unhandled rejection，
    // 被 vitest 归到**后面某个不相关的测试**头上报错。
    // 测试里造出来的悬空 promise 会污染其它测试的结论，比测试本身失败更难查。
    const { grabApi } = await import('@/api/client')
    const spy = vi.spyOn(grabApi, 'grab').mockRejectedValueOnce(new Error('boom'))

    await expect(grab(1001)).rejects.toThrow('boom')
    expect(isGrabbing(1001)).toBe(false)
    spy.mockRestore()
  })

  // ---------------------------------------------------------------- 坑 ③

  it('限流是正常态：4290 会自动退避重试而不是报错给用户', async () => {
    vi.useFakeTimers()
    const { grab } = useGrab()
    backend.queue = [
      { code: CODE.RATE_LIMITED },
      { code: CODE.RATE_LIMITED },
      { code: 200, message: '抢到了' }
    ]

    const p = grab(1001)
    await vi.runAllTimersAsync()
    const r = await p

    expect(r.code).toBe(200)
    expect(backend.calls.length).toBe(3)
  })

  it('终态直接返回，不做无意义的重试', async () => {
    const { grab } = useGrab()
    for (const code of [CODE.SOLD_OUT, CODE.ALREADY_BOUGHT, CODE.RISK_BLOCKED]) {
      backend.calls = []
      backend.queue = [{ code }]
      const r = await grab(2000 + code)
      expect(r.code).toBe(code)
      // 售罄了重试一百次也还是售罄 —— 重试只对限流有意义
      expect(backend.calls.length).toBe(1)
    }
  })

  it('重试用尽后把最后一次的限流结果返回，不假装成功', async () => {
    vi.useFakeTimers()
    const { grab } = useGrab()
    backend.queue = Array.from({ length: 10 }, () => ({ code: CODE.RATE_LIMITED }))

    const p = grab(1001, { maxRetry: 3 })
    await vi.runAllTimersAsync()
    const r = await p

    expect(r.code).toBe(CODE.RATE_LIMITED)
    expect(backend.calls.length).toBe(4)   // 首次 + 3 次重试
  })

  /**
   * 抖动不能省。没有抖动，所有客户端会在同一时刻同时重试，形成惊群，
   * 把刚缓下来的服务端再打下去 —— 和后端 AIMD 的超调是同一类问题：
   * 反馈环缺少阻尼。
   *
   * 测法：同一组重试跑两次，退避时长必须不同。用 onRetry 回调拿到实际时长。
   */
  it('退避时长带随机抖动 —— 否则所有客户端同步重试形成惊群', async () => {
    const collect = async () => {
      vi.useFakeTimers()
      const { grab } = useGrab()
      backend.calls = []
      backend.queue = [{ code: CODE.RATE_LIMITED }, { code: 200 }]
      const delays = []
      const p = grab(1001, { onRetry: (_a, _m, ms) => delays.push(ms) })
      await vi.runAllTimersAsync()
      await p
      return delays
    }

    const runs = []
    for (let i = 0; i < 8; i++) runs.push((await collect())[0])

    // 8 次里至少出现两个不同的值 —— 固定退避会让它们全部相等
    expect(new Set(runs).size).toBeGreaterThan(1)
  })

  it('退避按指数增长 —— 越挤越要往后让', async () => {
    vi.useFakeTimers()
    const { grab } = useGrab()
    backend.queue = Array.from({ length: 5 }, () => ({ code: CODE.RATE_LIMITED }))
    const delays = []

    const p = grab(1001, { maxRetry: 4, onRetry: (_a, _m, ms) => delays.push(ms) })
    await vi.runAllTimersAsync()
    await p

    expect(delays.length).toBe(4)
    // 抖动最多 200ms，而指数间隔是 200/400/800/1600，所以严格递增必须成立
    for (let i = 1; i < delays.length; i++) {
      expect(delays[i]).toBeGreaterThan(delays[i - 1])
    }
  })

  /**
   * 4291（风控降权）和 4290（限流）都要重试，但**退避基数不同**。
   *
   * 4290 是系统忙，重试大概率成功；4291 是行为被判定为异常，
   * 继续快速重试只会给风控喂更多证据、让自己被压得更狠。
   */
  it('风控降权 4291 也重试，但退避基数明显大于普通限流', async () => {
    const firstDelayFor = async (code) => {
      vi.useFakeTimers()
      const { grab } = useGrab()
      backend.calls = []
      backend.queue = [{ code }, { code: 200 }]
      let d = 0
      const p = grab(1001, { onRetry: (_a, _m, ms) => { d = ms } })
      await vi.runAllTimersAsync()
      await p
      return d
    }

    // 各取多次的最小值，排除抖动干扰
    const limited = Math.min(...await Promise.all([1, 2, 3].map(() => firstDelayFor(CODE.RATE_LIMITED))))
    const demoted = Math.min(...await Promise.all([1, 2, 3].map(() => firstDelayFor(CODE.RISK_DEMOTED))))

    expect(demoted).toBeGreaterThan(limited * 2)
  })

  it('重试进度可读，按钮才能显示「重试 2/5…」', async () => {
    vi.useFakeTimers()
    const { grab, retryState } = useGrab()
    backend.queue = [{ code: CODE.RATE_LIMITED }, { code: CODE.RATE_LIMITED }, { code: 200 }]

    const seen = []
    const p = grab(1001, { onRetry: (attempt, max) => seen.push([attempt, max]) })
    await vi.runAllTimersAsync()
    await p

    expect(seen).toEqual([[1, 5], [2, 5]])
    // 结束后要清干净，否则按钮会一直显示「重试中」
    expect(retryState.value[1001]).toBeUndefined()
  })
})
