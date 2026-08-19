/**
 * 日期工具。抽出来的唯一理由：**原来的实现有一个只在清晨出现的差一天 bug。**
 *
 * 原实现是 `new Date(Date.now() + 86400000).toISOString().slice(0, 10)`。
 * `toISOString()` 返回的是 **UTC** 日期，而患者选的是**本地**就诊日期。
 * 在 UTC+8，本地 00:00–08:00 之间 UTC 还停在前一天，于是整组日期往前偏一天。
 *
 * 实测（UTC+8 用户）：
 *
 * | 本地时刻 | 旧实现算出的"明天" | 正确的本地明天 |
 * |---|---|---|
 * | 07:00 | 2026-08-19 | 2026-08-20 ← 差一天 |
 * | 01:30 | 2026-08-19 | 2026-08-20 ← 差一天 |
 * | 09:00 | 2026-08-20 | 2026-08-20 |
 *
 * **这个 bug 的严重性完全来自业务场景**：真实医院普遍在 6:00 / 7:00 统一放号，
 * 而那正好落在坏掉的时间窗里。患者赶早起来抢号，页面默认给他选错了一天 ——
 * 要么看到空列表以为号没放，要么抢到了错误日期的号。
 * 白天测永远测不出来：09:00 之后结果是对的。
 *
 * 还有一个更隐蔽的副作用：旧实现的星期标签用 `d.getDay()`（**本地**），
 * 而日期串用 `toISOString()`（**UTC**）。两者在清晨会互相矛盾——
 * 显示「2026-08-19 周四」，而 08-19 本地其实是周三。
 * <b>同一个时刻用两套时区解释，是日期 bug 最常见的来源。</b>
 */

const pad = (n) => String(n).padStart(2, '0')

/** 按**本地**时区把 Date 格式成 `YYYY-MM-DD`。绝不要用 toISOString 做这件事。 */
export function toLocalDateString(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 从今天起偏移若干天，返回本地日期串。
 *
 * 用 `setDate(getDate() + n)` 而不是加 `n * 86400000` 毫秒：
 * 前者是**日历运算**，后者是**时间运算**。在有夏令时的地区，
 * 一天并不总是 86400000 毫秒，加毫秒会在切换日产生差一天。
 * 中国没有夏令时，所以这里两种写法结果相同 ——
 * 但一个日期工具没有理由只在特定时区正确。
 */
export function localDateOffset(days, from = new Date()) {
  const d = new Date(from.getTime())
  d.setDate(d.getDate() + days)
  return toLocalDateString(d)
}

const WEEKDAYS = '日一二三四五六'

/**
 * 生成日期选项：从今天起 `count` 天。
 *
 * 星期标签和日期串<b>必须来自同一个 Date 对象</b>，否则会出现
 * 「日期是 UTC 的、星期是本地的」这种自相矛盾的显示。
 */
export function dateOptions(count = 8, from = new Date()) {
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(from.getTime())
    d.setDate(d.getDate() + i)
    const value = toLocalDateString(d)
    const wd = WEEKDAYS[d.getDay()]
    const suffix = i === 0 ? '（今天）' : (i === 1 ? '（明天）' : '')
    return { value, label: `${value}　周${wd}${suffix}` }
  })
}
