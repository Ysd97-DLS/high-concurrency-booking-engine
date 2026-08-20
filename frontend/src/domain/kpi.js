/**
 * 运营看板 8 个 KPI 的装配与「什么算异常」的判定。
 *
 * 这段逻辑原来长在 AdminView.vue 里，而它的注释自己就写着
 * 「什么算异常是业务判断，不该散在模板里」—— 那句话是对的，
 * 只是当时抽了一半：从模板里挪进了 `<script setup>`，但没挪出组件。
 *
 * 挪出来的实际理由是**它坏了没人会知道**。这里每个 `t` 字段决定一张卡片
 * 显示成绿色还是红色，而阈值判反了的表现是<b>看板把异常显示成正常</b> ——
 * 运营每隔 3 秒看到一次「一切正常」，而积压正在涨。
 * 这正是这个项目反复踩的那一类：数字在动、颜色在变，描述的却是错的结论。
 *
 * 阈值本身的依据（都来自实测，不是拍的）：
 *   · 误拒率 20% —— L1 Agent 的唤醒阈值就是这个数，看板不该比控制面更晚报警
 *   · 积压 5000 / 20000 —— 前者是 L1 唤醒线（2000）的两倍多，后者在实测里
 *     对应「消费明显跟不上」（P6 那几轮卡死时积压都在 5 万以上）
 *   · 桶倾斜度 1 —— P3 热点画像实测到 1.155，而正常画像都在 0.03 以下
 */

/** 数字格式化。null/undefined 显示破折号而不是 0 —— 「没有这个数」和「这个数是 0」是两件事。 */
export function fmt(n) {
  return (n === null || n === undefined) ? '—' : Number(n).toLocaleString('zh-CN')
}

/** 误拒率的严重度。>50% 是红，>20% 是黄（和 L1 的唤醒阈值对齐）。 */
export function rejectTone(rate) {
  const r = rate || 0
  return r > 0.5 ? 'bad' : (r > 0.2 ? 'warn' : 'ok')
}

/** 积压的严重度。20000 以上在实测里意味着消费明显跟不上。 */
export function lagTone(lag) {
  const v = lag || 0
  return v > 20000 ? 'bad' : (v > 5000 ? 'warn' : 'ok')
}

/** 桶倾斜度的严重度。P3 热点画像实测 1.155，正常画像 0.03 以下。 */
export function skewTone(skew) {
  return (skew || 0) > 1 ? 'warn' : 'ok'
}

/**
 * 把看板接口的响应装配成 8 张 KPI 卡片。
 *
 * 所有取值都写成防御式（`?.` 加 `?? 0`），因为这个接口的字段是分块拼的
 * （performance / risk / release / pool / appointments），
 * 任何一块查询失败时对应的键就不存在 —— 而<b>看板缺一块不该整页白掉</b>。
 *
 * @param {object|null} d `/admin/dashboard` 的响应
 * @returns {Array<{k:string, v:string, s:string, t:string}>} k=标题 v=值 s=副标题 t=语义色
 */
export function buildKpis(d) {
  if (!d) return []

  const p = d.performance || {}
  const r = d.risk || {}
  const rel = d.release || {}
  const pool = d.pool || {}
  const pending = (d.appointments || []).find((a) => a.status === 'PENDING_PAY')

  const rate = p.rejectRate || 0
  const lag = p.streamPending || 0
  const skew = p.bucketSkew || 0

  return [
    {
      k: '号池余量',
      v: fmt(pool.globalRemaining),
      s: `排班 ${pool.scheduleId} · 桶 ${fmt(pool.bucketSum)} + 实例持有 ${fmt(pool.leaseHeld)}`,
      t: ''
    },
    {
      k: '放号进度',
      v: (rel.progressPercent ?? 0).toFixed(0) + '%',
      s: rel.releasing ? '正在分批放出' : '已放完',
      t: rel.releasing ? 'warn' : ''
    },
    {
      k: '有效 QPS',
      v: fmt(Math.round(p.effectiveQps || 0)),
      s: `请求 QPS ${fmt(Math.round(p.requestQps || 0))}`,
      t: ''
    },
    {
      k: '误拒率',
      v: (rate * 100).toFixed(1) + '%',
      s: `窗口 P99 ${(p.p99WindowMs || 0).toFixed(1)} ms`,
      t: rejectTone(rate)
    },
    {
      k: '消费积压',
      v: fmt(lag),
      s: '待落库的成交事件',
      t: lagTone(lag)
    },
    {
      k: '待支付',
      v: fmt(pending?.count ?? 0),
      s: '10 分钟后自动释放',
      t: ''
    },
    {
      k: '风控降权',
      v: fmt(r.demoted),
      s: `拉黑 ${fmt(r.blocked)} · 黑名单 ${fmt(r.blocklistSize)} 人`,
      t: (r.demoted || 0) > 0 ? 'warn' : 'ok'
    },
    {
      k: '桶倾斜度',
      v: skew.toFixed(3),
      s: `号段命中 ${((p.segmentHitRatio ?? 0) * 100).toFixed(1)}%`,
      t: skewTone(skew)
    }
  ]
}
