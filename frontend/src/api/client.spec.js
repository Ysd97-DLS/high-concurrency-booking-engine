import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  adminApi, controlApi, clinicApi, grabApi, CODE, CODE_TEXT,
  setPatientToken, getPatientToken, UnauthenticatedError
} from './client'

/**
 * 接口封装层的契约测试。
 *
 * 这里测的不是「能不能发请求」（那要起后端），而是**拼出来的 URL 对不对**。
 * 之所以值得测，是因为这一层的错误有个共同特征：
 * <b>请求照样成功、界面照样正常，只是做的事和你以为的不一样。</b>
 *
 * 这个项目里已经栽过三次同一形状的跟头：
 *   · 前端读 `guardNote` 而后端字段叫 `note` → 护栏的解释静默消失
 *   · 放号脚本读 `$s.id` 而字段叫 `scheduleId` → 循环空转，报「0 成功 0 跳过」
 *   · dashboard 字段名改过之后脚本的 `-not $null` 恒为真 → 假警报
 * 全都是「字段/参数名错了但没有任何东西报错」。所以契约要钉在测试里。
 */
describe('api client', () => {
  let calls

  beforeEach(() => {
    calls = []
    // 只记录 URL 和 method，不关心响应内容
    vi.stubGlobal('fetch', vi.fn(async (url, options) => {
      calls.push({ url, method: options?.method ?? 'GET', headers: options?.headers ?? {} })
      return { ok: true, text: async () => '{}' }
    }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const lastUrl = () => calls[calls.length - 1].url
  const lastMethod = () => calls[calls.length - 1].method
  const lastHeaders = () => calls[calls.length - 1].headers

  // ---------- 对账补偿：安全相关的默认值 ----------

  describe('对账补偿的 dryRun 参数', () => {
    it('不传参数时默认 dryRun=true（安全侧）', async () => {
      await adminApi.reconcileRun()
      // 这个接口会改动号源账目，默认值必须是「只预演」。
      // 后端 @RequestParam 的默认值也是 true，两边必须一致 ——
      // 只有一边是 true 的话，行为取决于「参数有没有被传过去」，那是最坏的情况。
      expect(lastUrl()).toContain('dryRun=true')
      expect(lastMethod()).toBe('POST')
    })

    it('dryRun=false 必须真的发出去，不能被 qs 当空值丢掉', async () => {
      await adminApi.reconcileRun(false)
      // **这是这个文件里最重要的一条断言。**
      // qs() 过滤空值时如果写成 `if (v)` 而不是逐个比较 undefined/null/''，
      // `false` 会被当成空值丢掉，请求变成不带 dryRun ——
      // 而后端默认 true，于是运营点「执行」实际只做了预演。
      // 号源没被补回去，界面却显示调用成功，账目继续不平。
      expect(lastUrl()).toContain('dryRun=false')
      expect(lastUrl()).not.toMatch(/dryRun=true/)
    })

    it('history 带 limit', async () => {
      await adminApi.reconcileHistory(7)
      expect(lastUrl()).toContain('limit=7')
      expect(lastMethod()).toBe('GET')
    })
  })

  // ---------- qs 的空值语义 ----------

  describe('查询串拼接的空值语义', () => {
    it('false 和 0 是有效值，必须发送', async () => {
      // 挂号域里 0 是有意义的：release.spreadSeconds=0 表示「一次放完」。
      // 把 0 过滤掉会让这个参数永远调不成 0。
      await controlApi.setConfig('release.spreadSeconds', 0, '一次放完')
      expect(lastUrl()).toContain('value=0')
    })

    it('undefined / null / 空串 才被过滤', async () => {
      await clinicApi.schedules(1, undefined)
      expect(lastUrl()).toContain('departmentId=1')
      // 不传日期时后端默认查明天，所以不能拼出 date=undefined
      expect(lastUrl()).not.toContain('date=')
      expect(lastUrl()).not.toContain('undefined')
    })
  })

  // ---------- 业务码表 ----------

  describe('业务码', () => {
    it('4290 限流和 4291 风控降权是两个不同的码', () => {
      // 合并成一个码的代价实测过：52 万次风控丢弃被报成「限流拒绝」，
      // 整轮压测结论被污染而报告上看不出异常。
      expect(CODE.RATE_LIMITED).not.toBe(CODE.RISK_DEMOTED)
      expect(CODE.RATE_LIMITED).toBe(4290)
      expect(CODE.RISK_DEMOTED).toBe(4291)
    })

    it('每个业务码都有对应的用户可读文案', () => {
      // 缺文案的话界面会显示 undefined，而这只在那个分支被触发时才暴露 ——
      // 恰好是最少见、最需要说清楚的那些分支（拉黑、降权）。
      for (const [name, code] of Object.entries(CODE)) {
        expect(CODE_TEXT[code], `业务码 ${name}(${code}) 缺少文案`).toBeTruthy()
      }
    })
  })

  // ---------- 传输层 vs 业务层 ----------

  describe('错误分层', () => {
    it('业务错误走 HTTP 200 + code，不抛异常', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => ({
        ok: true,
        text: async () => JSON.stringify({ code: 4001, message: '号源已满' })
      })))
      // 「售罄」是高频正常路径，不能当异常抛 —— 否则调用方要用 try/catch 处理正常业务分支
      const r = await clinicApi.detail('A1-1')
      expect(r.code).toBe(4001)
    })

    it('只有传输层错误才抛', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, text: async () => '' })))
      await expect(clinicApi.departments()).rejects.toThrow(/503/)
    })
  })

  // ---------- 患者身份 ----------
  //
  // 这一组守的是安全修复的前端一半。后端已经不接受 ?patientId= / ?holderId= 了，
  // 前端要是漏了带令牌，表现是**所有操作静默 401**；
  // 而要是漏了把身份从查询串里去掉，那只是多传一个被忽略的参数 ——
  // 没有任何东西会报错，但很容易让人误以为身份还是前端说了算。

  describe('患者身份', () => {
    beforeEach(() => {
      setPatientToken(null)
    })

    it('有令牌时每个请求都带上 X-Patient-Token', async () => {
      setPatientToken('5501.sig')
      await clinicApi.myAppointments()
      expect(lastHeaders()['X-Patient-Token']).toBe('5501.sig')
    })

    it('没有令牌时不带这个头，而不是带一个空值', async () => {
      // 带 `X-Patient-Token: ` 空串会让后端走验签失败分支而不是「没带令牌」分支，
      // 两者最终都是 401，但日志里分不清是「没登录」还是「令牌被篡改」。
      await clinicApi.departments()
      expect(lastHeaders()['X-Patient-Token']).toBeUndefined()
    })

    it('我的预约不再传 patientId —— 身份只能来自令牌', async () => {
      setPatientToken('5501.sig')
      await clinicApi.myAppointments(30)
      expect(lastUrl()).toBe('/clinic/appointments?limit=30')
      expect(lastUrl()).not.toContain('patientId')
    })

    it('抢号不再传 holderId —— 风控的计数键不能由客户端指定', async () => {
      setPatientToken('5501.sig')
      await grabApi.grab(20016, 'web-abc')
      expect(lastUrl()).toBe('/seckill/20016?deviceId=web-abc')
      expect(lastUrl()).not.toContain('holderId')
    })

    it('就诊登记走 /admin —— 它是院方操作，不该留在患者端', async () => {
      // 原来是 /clinic/appointments/{no}/no-show 且无校验：
      // 调三次就能把一个真实患者禁约 30 天。
      await adminApi.noShow('A1-1')
      expect(lastUrl()).toBe('/admin/appointments/A1-1/no-show')
      await adminApi.complete('A1-1')
      expect(lastUrl()).toBe('/admin/appointments/A1-1/complete')
    })

    it('401 抛 UnauthenticatedError，并且清掉失效的令牌', async () => {
      setPatientToken('stale.sig')
      vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 401, text: async () => '{}' })))

      await expect(clinicApi.myAppointments()).rejects.toBeInstanceOf(UnauthenticatedError)
      // 留着失效令牌只会让后续每个请求都继续 401
      expect(getPatientToken()).toBeNull()
    })

    it('401 和 5xx 是两个不同的类型 —— 重试策略相反', async () => {
      // 401 重试一万次也没用（要去换令牌），5xx 重试大概率成功。
      // useGrab 的退避逻辑要靠这个区分，混成一类会让用户对着 401 空转五次退避。
      vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, text: async () => '' })))
      await expect(clinicApi.myAppointments()).rejects.not.toBeInstanceOf(UnauthenticatedError)
    })

    it('sessionStorage 不可用时降级到内存，不能让所有请求跟着挂', async () => {
      // Safari 无痕、隐私设置、SSR 都可能让它不可用。
      // 这段代码在每个请求的路径上，一次异常就是整个应用全挂 ——
      // 而降级的代价只是「刷新页面要重新换令牌」。
      const original = globalThis.sessionStorage
      Object.defineProperty(globalThis, 'sessionStorage', {
        configurable: true,
        get() { throw new Error('SecurityError') }
      })
      try {
        setPatientToken('mem.sig')
        expect(getPatientToken()).toBe('mem.sig')
        await clinicApi.departments()
        expect(lastHeaders()['X-Patient-Token']).toBe('mem.sig')
      } finally {
        if (original === undefined) delete globalThis.sessionStorage
        else Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: original })
      }
    })
  })
})
