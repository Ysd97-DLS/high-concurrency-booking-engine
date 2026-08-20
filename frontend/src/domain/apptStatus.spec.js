import { describe, it, expect } from 'vitest'
import { STATUS_META, statusMeta, canPay } from './apptStatus.js'

/**
 * 六状态到界面的映射。
 *
 * 真正要钉住的是 `action` —— 它决定页面上出现什么按钮。
 * 判错的后果不是「显示难看」，而是**给用户一个点了必然报错的按钮**：
 * 他会先以为这张失效的单还能救，试几次，然后才明白发生了什么。
 * 「能点但点了出错」比「不能点」难排查得多，而且它先浪费的是用户的时间。
 */

describe('六个状态都有映射', () => {
  it('和后端 ApptStatus 的六个值一一对应', () => {
    expect(Object.keys(STATUS_META)).toEqual([
      'PENDING_PAY', 'BOOKED', 'EXPIRED', 'REFUNDED', 'COMPLETED', 'NO_SHOW'
    ])
  })

  it('每个状态都有完整的四个字段', () => {
    for (const [status, meta] of Object.entries(STATUS_META)) {
      expect(meta.label, status).toBeTruthy()
      expect(meta.type, status).toBeTruthy()
      expect(meta, status).toHaveProperty('action')   // 可以是 null，但键必须在
      expect(meta, status).toHaveProperty('note')
    }
  })
})

describe('哪些状态允许操作 —— 这一组决定按钮', () => {
  it('只有待支付能付款', () => {
    const payable = Object.entries(STATUS_META)
      .filter(([, m]) => m.action === 'pay').map(([s]) => s)
    expect(payable).toEqual(['PENDING_PAY'])
  })

  it('只有已预约能退号', () => {
    const refundable = Object.entries(STATUS_META)
      .filter(([, m]) => m.action === 'refund').map(([s]) => s)
    expect(refundable).toEqual(['BOOKED'])
  })

  it('**终态一律没有任何 action** —— 号已经不在这张单手里了', () => {
    for (const s of ['EXPIRED', 'REFUNDED', 'COMPLETED', 'NO_SHOW']) {
      expect(STATUS_META[s].action, s).toBeNull()
    }
  })
})

describe('需要向用户解释的状态带 note', () => {
  it('已失效要说清号去哪了', () => {
    expect(STATUS_META.EXPIRED.note).toContain('号源已释放回号池')
  })

  it('已失约必须告知后果 —— 否则黑名单对用户是个黑箱', () => {
    // 他只知道自己突然约不上了，不知道为什么、也不知道多久
    expect(STATUS_META.NO_SHOW.note).toContain('3 次')
    expect(STATUS_META.NO_SHOW.note).toContain('30 天')
  })

  it('失约用 danger 色 —— 它是唯一会导致后续处罚的状态', () => {
    expect(STATUS_META.NO_SHOW.type).toBe('danger')
  })
})

describe('未知状态的兜底', () => {
  it('后端加了新状态而前端没跟上时，不崩，原样显示', () => {
    const m = statusMeta('SOME_NEW_STATE')
    expect(m.label).toBe('SOME_NEW_STATE')
    expect(m.type).toBe('info')
  })

  it('**未知状态一律没有 action** —— 宁可让用户什么都点不了，也不给会报错的按钮', () => {
    expect(statusMeta('SOME_NEW_STATE').action).toBeNull()
    expect(statusMeta(undefined).action).toBeNull()
    expect(statusMeta(null).action).toBeNull()
    expect(statusMeta('').action).toBeNull()
  })
})

describe('能不能付款 —— 「待支付」和「能付款」不是同一件事', () => {
  const at = (ms) => ({ status: 'PENDING_PAY', payDeadlineMs: ms })

  it('未过期的待支付单能付', () => {
    expect(canPay(at(2000), 1000)).toBe(true)
  })

  it('恰好到截止时刻还能付 —— 边界给用户', () => {
    expect(canPay(at(1000), 1000)).toBe(true)
  })

  it('过了截止时刻不能付', () => {
    expect(canPay(at(1000), 1001)).toBe(false)
  })

  it('非待支付状态一概不能付', () => {
    for (const s of ['BOOKED', 'EXPIRED', 'REFUNDED', 'COMPLETED', 'NO_SHOW']) {
      expect(canPay({ status: s, payDeadlineMs: 9e15 }), s).toBe(false)
    }
  })

  it('缺截止时间时不拦 —— 后端理应总是给，但少了不该导致付不了钱', () => {
    expect(canPay({ status: 'PENDING_PAY' })).toBe(true)
    expect(canPay({ status: 'PENDING_PAY', payDeadlineMs: null })).toBe(true)
  })

  it('空对象不崩', () => {
    expect(canPay(null)).toBe(false)
    expect(canPay(undefined)).toBe(false)
    expect(canPay({})).toBe(false)
  })
})
