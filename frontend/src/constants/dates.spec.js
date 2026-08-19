import { describe, it, expect } from 'vitest'
import { toLocalDateString, localDateOffset, dateOptions } from './dates'

/**
 * 日期工具测试。
 *
 * 这组测试守的是一个**只在清晨出现**的 bug：原实现用 `toISOString()` 取日期，
 * 而它返回 UTC。UTC+8 的本地 00:00–08:00 之间 UTC 还停在前一天，日期整体偏移一天。
 *
 * 严重性完全来自业务场景：**放号在 6:00 / 7:00**，正好落在坏掉的窗口里；
 * 而 09:00 之后结果又是对的，白天怎么点都发现不了。
 *
 * 所以每个用例都<b>显式传入一个固定的"现在"</b>，把清晨那几个小时钉在测试里。
 * 依赖真实 `new Date()` 的测试在早上会红、下午会绿，那种测试等于没有。
 */
describe('日期工具（本地时区，不用 UTC）', () => {

  /** 构造一个「本地时钟显示为 y-m-d h:mi」的 Date，不受运行机器时区影响。 */
  const localAt = (y, m, d, h, mi = 0) => new Date(y, m - 1, d, h, mi, 0, 0)

  describe('toLocalDateString', () => {
    it('用本地年月日格式化，不做时区换算', () => {
      expect(toLocalDateString(localAt(2026, 8, 19, 7, 0))).toBe('2026-08-19')
      expect(toLocalDateString(localAt(2026, 8, 19, 1, 30))).toBe('2026-08-19')
      expect(toLocalDateString(localAt(2026, 8, 19, 23, 50))).toBe('2026-08-19')
    })

    it('月份和日期补零', () => {
      expect(toLocalDateString(localAt(2026, 1, 5, 12))).toBe('2026-01-05')
    })
  })

  describe('localDateOffset —— 清晨不能差一天', () => {
    // 这四个时刻里，前两个正是旧实现出错的窗口（本地 00:00–08:00）。
    it.each([
      ['07:00 放号高峰', localAt(2026, 8, 19, 7, 0), '2026-08-20'],
      ['01:30 深夜',     localAt(2026, 8, 19, 1, 30), '2026-08-20'],
      ['09:00 上午',     localAt(2026, 8, 19, 9, 0), '2026-08-20'],
      ['23:50 临近午夜', localAt(2026, 8, 19, 23, 50), '2026-08-20']
    ])('%s 的"明天"都应是 %s', (_label, now, expected) => {
      expect(localDateOffset(1, now)).toBe(expected)
    })

    it('offset 0 是今天', () => {
      expect(localDateOffset(0, localAt(2026, 8, 19, 6, 0))).toBe('2026-08-19')
    })

    it('跨月正确', () => {
      expect(localDateOffset(1, localAt(2026, 8, 31, 7, 0))).toBe('2026-09-01')
    })

    it('跨年正确', () => {
      expect(localDateOffset(1, localAt(2026, 12, 31, 7, 0))).toBe('2027-01-01')
    })

    it('闰年 2 月正确', () => {
      expect(localDateOffset(1, localAt(2028, 2, 28, 7, 0))).toBe('2028-02-29')
    })

    it('不修改传入的 Date（纯函数）', () => {
      const now = localAt(2026, 8, 19, 7, 0)
      const before = now.getTime()
      localDateOffset(7, now)
      expect(now.getTime()).toBe(before)
    })
  })

  describe('dateOptions', () => {
    it('从今天起连续 8 天，无重复无跳号', () => {
      const opts = dateOptions(8, localAt(2026, 8, 19, 7, 0))
      expect(opts).toHaveLength(8)
      expect(opts[0].value).toBe('2026-08-19')
      expect(opts[7].value).toBe('2026-08-26')
      expect(new Set(opts.map((o) => o.value)).size).toBe(8)
    })

    it('星期标签必须和日期串来自同一时区', () => {
      // 旧实现里日期串是 UTC（toISOString）而星期是本地（getDay），
      // 清晨会显示成「2026-08-19 周四」，而 08-19 本地其实是周三。
      // 同一个时刻用两套时区解释，是日期 bug 最常见的来源。
      const opts = dateOptions(8, localAt(2026, 8, 19, 7, 0))
      const names = '日一二三四五六'
      for (const o of opts) {
        const [y, m, d] = o.value.split('-').map(Number)
        const expected = names[new Date(y, m - 1, d).getDay()]
        expect(o.label).toContain(`周${expected}`)
      }
    })

    it('首项标「今天」、次项标「明天」，其余不加后缀', () => {
      const opts = dateOptions(3, localAt(2026, 8, 19, 7, 0))
      expect(opts[0].label).toContain('（今天）')
      expect(opts[1].label).toContain('（明天）')
      expect(opts[2].label).not.toContain('（')
    })

    it('跨月边界连续', () => {
      const opts = dateOptions(3, localAt(2026, 8, 30, 7, 0))
      expect(opts.map((o) => o.value)).toEqual(['2026-08-30', '2026-08-31', '2026-09-01'])
    })
  })

  describe('回归：绝不能再用 toISOString 取日期', () => {
    it('清晨时 localDateOffset 与 toISOString 的结果应当【不同】', () => {
      // 这条用例的作用是「证明这个 bug 真的存在过」，同时锁住修复方向：
      // 如果有人把实现改回 toISOString，两者会相等，这里就会失败。
      const now = localAt(2026, 8, 19, 7, 0)
      const offsetMs = now.getTimezoneOffset()
      if (offsetMs >= 0) {
        // 运行机器在 UTC 或西侧时区时这个差异不出现，跳过而不是假装通过
        return
      }
      const viaIso = new Date(now.getTime() + 86400000).toISOString().slice(0, 10)
      expect(localDateOffset(1, now)).not.toBe(viaIso)
    })
  })
})
