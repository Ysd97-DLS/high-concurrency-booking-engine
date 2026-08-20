/**
 * 预约单的六个状态怎么呈现给患者。
 *
 * 抽出来是因为 `action` 这个字段**决定页面上出现什么按钮**，而它是业务判断不是渲染：
 * 已失效的单如果 action 不是 null，患者会看到一个「支付」按钮 ——
 * 点下去必然报错，而在那之前他会以为这张单还能救。
 *
 * `note` 同理。失约的后果（累计 3 次限制预约 30 天）必须让用户看见，
 * 否则黑名单对用户来说是个黑箱：他只知道自己突然约不上了，不知道为什么、也不知道多久。
 *
 * 放在 domain 而不是视图里，和后端的 clinic/domain 是同一个理由 ——
 * 这些规则会被问「为什么」，而模板里的三元表达式回答不了。
 */

/** 六个状态的完整映射。顺序按状态机的推进顺序排，便于和 ApptStatus.java 对读。 */
export const STATUS_META = {
  PENDING_PAY: { label: '待支付', type: 'warning', action: 'pay', note: '' },
  BOOKED: { label: '已预约', type: 'success', action: 'refund', note: '' },
  EXPIRED: { label: '已失效', type: 'info', action: null, note: '超时未支付，号源已释放回号池' },
  REFUNDED: { label: '已退号', type: 'info', action: null, note: '号源已归还' },
  COMPLETED: { label: '已就诊', type: 'primary', action: null, note: '' },
  // 失约的后果必须让用户看见，否则黑名单对用户来说是个黑箱
  NO_SHOW: { label: '已失约', type: 'danger', action: null, note: '累计 3 次失约将限制预约 30 天' }
}

/**
 * 取一个状态的呈现信息。
 *
 * <b>未知状态一律没有 action。</b>后端加了新状态而前端还没跟上时，
 * 宁可让用户什么都点不了（他会去问），也不能给他一个会报错的按钮 ——
 * 「能点但点了出错」比「不能点」难排查得多，而且它先浪费的是用户的时间。
 *
 * @param {string} status 后端返回的状态字符串
 */
export function statusMeta(status) {
  return STATUS_META[status] || { label: status, type: 'info', action: null, note: '' }
}

/** 这张单还能不能付款。抽成一个名字是因为「待支付」和「能付款」不是同一件事 —— 过期的待支付单不能付。 */
export function canPay(appt, nowMs = Date.now()) {
  if (!appt || appt.status !== 'PENDING_PAY') return false
  // 没有截止时间的单不拦（后端理应总是给，但少了不该导致付不了钱）
  if (appt.payDeadlineMs === null || appt.payDeadlineMs === undefined) return true
  return nowMs <= appt.payDeadlineMs
}
