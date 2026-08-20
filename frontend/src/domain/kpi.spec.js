import { describe, it, expect } from 'vitest'
import { buildKpis, fmt, rejectTone, lagTone, skewTone } from './kpi.js'

/**
 * 看板 KPI 的装配与阈值判定。
 *
 * 为什么值得测：这里每个 `t` 字段决定一张卡片显示成绿色还是红色，
 * 而阈值判反了的表现是**看板把异常显示成正常** ——
 * 运营每隔 3 秒看到一次「一切正常」，而积压正在涨。
 *
 * 另一半是**字段缺失**。看板接口的响应是分块拼的，任何一块查询失败对应的键就不存在，
 * 而看板缺一块不该整页白掉。这类边界在真实环境里很难复现（要故意让某个查询失败），
 * 在这里两行就能覆盖。
 */

describe('数字格式化', () => {
  it('千分位', () => {
    expect(fmt(1234567)).toBe('1,234,567')
  })

  it('null 和 undefined 显示破折号，不是 0 —— 「没有这个数」和「这个数是 0」是两件事', () => {
    expect(fmt(null)).toBe('—')
    expect(fmt(undefined)).toBe('—')
    expect(fmt(0)).toBe('0')
  })
})

describe('误拒率的严重度', () => {
  it('20% 以下算正常', () => {
    expect(rejectTone(0)).toBe('ok')
    expect(rejectTone(0.2)).toBe('ok')       // 恰好 20% 不算异常
  })

  it('超过 20% 转黄 —— 和 L1 Agent 的唤醒阈值对齐，看板不该比控制面更晚报警', () => {
    expect(rejectTone(0.201)).toBe('warn')
    expect(rejectTone(0.5)).toBe('warn')     // 恰好 50% 还是黄
  })

  it('超过 50% 转红', () => {
    expect(rejectTone(0.51)).toBe('bad')
    expect(rejectTone(0.95)).toBe('bad')
  })

  it('缺字段按 0 算，不能崩也不能误报红', () => {
    expect(rejectTone(undefined)).toBe('ok')
    expect(rejectTone(null)).toBe('ok')
  })
})

describe('积压的严重度', () => {
  it('5000 以下正常，超过转黄，超过 20000 转红', () => {
    expect(lagTone(0)).toBe('ok')
    expect(lagTone(5000)).toBe('ok')
    expect(lagTone(5001)).toBe('warn')
    expect(lagTone(20000)).toBe('warn')
    expect(lagTone(20001)).toBe('bad')
  })

  it('实测数据点：P6 卡死那几轮积压都在 5 万以上，必须是红的', () => {
    expect(lagTone(56866)).toBe('bad')
    expect(lagTone(99225)).toBe('bad')
  })
})

describe('桶倾斜度的严重度', () => {
  it('正常画像实测 0.03 以下 → ok；P3 热点画像实测 1.155 → warn', () => {
    expect(skewTone(0.03)).toBe('ok')
    expect(skewTone(1)).toBe('ok')          // 恰好 1 不算
    expect(skewTone(1.155)).toBe('warn')
    // 批量放号堆一个桶的那个 bug 实测到 8.0，必须是 warn
    expect(skewTone(8.0)).toBe('warn')
  })
})

describe('KPI 装配', () => {
  const full = {
    pool: { scheduleId: 20006, globalRemaining: 50, bucketSum: 45, leaseHeld: 5 },
    release: { progressPercent: 100, releasing: false },
    performance: {
      effectiveQps: 2136.4, requestQps: 16814.7, rejectRate: 0.13,
      streamPending: 1200, p99WindowMs: 25.5, bucketSkew: 0.03, segmentHitRatio: 0.9524
    },
    risk: { demoted: 0, blocked: 0, blocklistSize: 0 },
    appointments: [{ status: 'PENDING_PAY', count: 7 }, { status: 'BOOKED', count: 3 }]
  }

  it('装出 8 张卡片，顺序固定', () => {
    const k = buildKpis(full)
    expect(k).toHaveLength(8)
    expect(k.map((x) => x.k)).toEqual([
      '号池余量', '放号进度', '有效 QPS', '误拒率',
      '消费积压', '待支付', '风控降权', '桶倾斜度'
    ])
  })

  it('健康系统的卡片没有红色', () => {
    const tones = buildKpis(full).map((x) => x.t)
    expect(tones).not.toContain('bad')
    expect(tones).not.toContain('warn')
  })

  it('数值格式正确', () => {
    const k = buildKpis(full)
    expect(k[0].v).toBe('50')
    expect(k[1].v).toBe('100%')
    expect(k[2].v).toBe('2,136')          // 四舍五入 + 千分位
    expect(k[3].v).toBe('13.0%')
    expect(k[4].v).toBe('1,200')
    expect(k[5].v).toBe('7')              // 从 appointments 里挑 PENDING_PAY
    expect(k[7].v).toBe('0.030')          // 三位小数
  })

  it('副标题带上下文，而不是只有一个孤零零的数', () => {
    const k = buildKpis(full)
    expect(k[0].s).toContain('排班 20006')
    expect(k[0].s).toContain('桶 45')
    expect(k[2].s).toContain('16,815')     // 请求 QPS 也四舍五入
    expect(k[7].s).toContain('95.2%')      // 号段命中率
  })

  it('null 响应返回空数组，不抛异常', () => {
    expect(buildKpis(null)).toEqual([])
    expect(buildKpis(undefined)).toEqual([])
  })

  it('**整块字段缺失时不崩** —— 看板缺一块不该整页白掉', () => {
    // 只有 pool，其余四块全没有（模拟 Promise.allSettled 里几个 rejected）
    const k = buildKpis({ pool: { scheduleId: 1001, globalRemaining: 100 } })
    expect(k).toHaveLength(8)
    expect(k[0].v).toBe('100')
    expect(k[1].v).toBe('0%')             // release 缺失 → 0%
    expect(k[3].v).toBe('0.0%')           // performance 缺失 → 误拒率 0
    expect(k[5].v).toBe('0')              // appointments 缺失 → 待支付 0
    expect(k[6].v).toBe('—')              // risk 缺失 → 破折号（不是 0）
  })

  it('缺失的块一律判成正常，不能凭空报红', () => {
    const tones = buildKpis({}).map((x) => x.t)
    expect(tones).not.toContain('bad')
  })

  it('过载状态：误拒率和积压同时转红', () => {
    const k = buildKpis({
      ...full,
      performance: { ...full.performance, rejectRate: 0.78, streamPending: 56866, bucketSkew: 1.155 }
    })
    expect(k[3].t).toBe('bad')    // 误拒率 78%
    expect(k[4].t).toBe('bad')    // 积压 56866
    expect(k[7].t).toBe('warn')   // 倾斜度 1.155
  })

  it('放号中的排班标黄 —— 那时候「余量少」是正常的，不该被误读成售罄', () => {
    const k = buildKpis({ ...full, release: { progressPercent: 37, releasing: true } })
    expect(k[1].v).toBe('37%')
    expect(k[1].t).toBe('warn')
    expect(k[1].s).toBe('正在分批放出')
  })

  it('风控降权非零时转黄', () => {
    const k = buildKpis({ ...full, risk: { demoted: 12, blocked: 3, blocklistSize: 1 } })
    expect(k[6].t).toBe('warn')
    expect(k[6].s).toContain('黑名单 1 人')
  })
})
