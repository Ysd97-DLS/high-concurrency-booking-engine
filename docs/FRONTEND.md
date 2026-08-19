# 前端说明：接口清单与四个必踩的坑

> **这份文档讲的是「为什么」，工程说明在 [frontend/README.md](../frontend/README.md)。**
>
> 前端现在是 `frontend/` 下的 Vue 3 + Vite 应用，构建产物打进 jar，
> 启动后访问 `http://localhost:8090/` 即可（患者端和运营看板都在里面）。
> 最早的 `static/` 静态参考页已删除——它的作用是先把逻辑写清楚，那个目的已经达到了。
>
> 下面这四个坑是当时那份参考实现里最值得保留的部分，现在分别封装在：
>
> | 坑 | 落在哪 |
> |---|---|
> | ① 防重复提交 | `frontend/src/composables/useGrab.js` |
> | ② 倒计时时钟校准 | `useServerClock.js` + `useCountdown.js` |
> | ③ 429 退避重试 | `useGrab.js` |
> | ④ 六状态机的 UI 表达 | `views/MineView.vue` 的 `STATUS_META` |
>
> **换任何框架都要重踩一遍，所以理解它们比理解某个框架的写法重要。**

---

## 一、四个坑（重写时最该保留的部分）

代码可以重写，这四处逻辑不该重新踩一遍。它们都不是"前端技巧"，
而是**分布式系统的约束反映到前端**的结果，面试也常问。

### 坑 ① 防重复提交

放号瞬间用户一定会狂点按钮。不挡的后果不只是浪费请求：

- **浪费自己的限流配额**——越点越抢不到，因为主限流是全局共享的
- **被风控当成黄牛**——频次判据会把狂点的真实患者识别成异常

做两层，因为单层都能被绕过：

```js
const inFlight = new Set();        // 以 scheduleId 为粒度，不同号池互不影响

async function grab(scheduleId){
  if (inFlight.has(scheduleId)) return;   // 第二层：防止绕过按钮（快速回车、脚本）
  inFlight.add(scheduleId);
  btn.disabled = true;                    // 第一层：视觉反馈
  try { /* … */ } finally {
    inFlight.delete(scheduleId);          // finally 里释放，异常路径也要恢复
    btn.disabled = false;
  }
}
```

> 只置灰按钮不够：用户按住回车、或者页面被脚本驱动时按钮状态不参与判断。

### 坑 ② 支付倒计时必须对齐服务端时间

**绝对不能用浏览器的 `Date.now()` 直接算剩余秒数。**用户的系统时间可能偏几分钟
甚至几小时（虚拟机、手动改过时区的机器很常见）。后果是双向的：

| 本地时钟 | 后果 |
|---|---|
| 偏快 | 还能支付的单显示"已超时"，用户直接放弃 |
| 偏慢 | 早该释放的单一直倒计时，用户点了支付才发现失败 |

做法是启动时校准一次偏移量，之后所有倒计时都用校准后的时间：

```js
let clockSkewMs = 0;

async function syncClock(){
  const t0 = Date.now();
  const r  = await fetch('/clinic/server-time').then(r => r.json());
  const t1 = Date.now();
  clockSkewMs = r.epochMs - (t0 + (t1 - t0) / 2);   // 减掉半个 RTT
}
const serverNow = () => Date.now() + clockSkewMs;
```

后端接口 `GET /clinic/server-time` 就是为此存在的。

两个配套细节：

- **一个 timer 驱动所有行**，不要每行一个 `setInterval`——几十行各自计时很浪费
- **倒计时归零时刷新列表**，让服务端的真实状态覆盖前端的推算。
  前端算出来的"已超时"只是猜测，真正的状态转移由后端的 3 秒扫描任务决定

### 坑 ③ 429 要退避重试，不是报错

抢号被限流（业务码 `4290`）是**正常态**而不是异常——放号瞬间绝大多数请求都会被挡掉。
弹一个"系统繁忙"让用户自己再点，等于把重试责任推给用户，而且会引发坑 ①。

用指数退避 + **抖动**：

```js
for (let attempt = 0; attempt <= 5; attempt++){
  const r = await post(`/seckill/${id}?holderId=${pid}&deviceId=${dev}`);
  if (r.code !== 4290) return r;                    // 成功/售罄/重复都是终态
  const backoff = 200 * 2 ** attempt + Math.random() * 200;   // 抖动不能省
  await sleep(backoff);
}
```

> **抖动为什么不能省**：没有它，所有客户端会在同一时刻同时重试，
> 形成一波又一波的同步冲击（惊群），把刚缓下来的服务端再打下去。
> 这和服务端的 AIMD 控制律是同一类问题——**同步的重试比高并发更可怕**。

### 坑 ④ 状态机要在 UI 上表达出来

预约单有六种状态，各自允许的操作完全不同。不能所有行都显示同一组按钮。

| 状态 | 可用操作 | UI 表达 |
|---|---|---|
| `PENDING_PAY` 待支付 | 支付 | 支付按钮 + **倒计时** |
| `BOOKED` 已预约 | 退号 | 退号按钮（危险色）|
| `EXPIRED` 已失效 | 无 | 灰字"超时未支付，号源已释放" |
| `REFUNDED` 已退号 | 无 | 灰字 |
| `COMPLETED` 已就诊 | 无 | 灰字 |
| `NO_SHOW` 已失约 | 无 | **提示累计 3 次会限制预约** |

最后一行尤其重要：失约的后果必须让用户看见，否则黑名单机制对用户来说是个黑箱。

---

## 二、接口清单

所有业务错误都走 **HTTP 200 + `code` 字段**，只有传输层错误才是非 200。
前端应该按 `code` 分支，不要用 HTTP 状态判断业务结果。

### 抢号（引擎热路径）

```
POST /seckill/{poolId}?holderId={患者ID}&deviceId={设备指纹}
```

| code | 含义 | 前端处置 |
|---|---|---|
| `200` | 抢号成功，预约单正在生成 | 跳到「我的预约」，提示 10 分钟内支付 |
| `4001` | 号源已满 | 提示已满，刷新列表 |
| `4002` | 已预约过该医生当天的号 | 提示，引导去「我的预约」 |
| `4290` | 被限流 | **退避重试**，见坑 ③ |
| `4030` | 命中失约黑名单 | 展示后端返回的 message |
| `5000` | 系统繁忙 | 提示重试 |

> `deviceId` 是风控 L2 判据的输入（识别"一机多号"批量代抢）。
> 前端用持久化的随机 ID 即可；真实系统会用更完整的指纹（Canvas / 字体 / WebGL）。
> 注意它**故意持久化**——清掉就等于换了台设备。

### 患者端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/clinic/server-time` | 服务端时间，用于校准倒计时（坑 ②）|
| GET | `/clinic/departments` | 科室列表 |
| GET | `/clinic/schedules?departmentId=&date=` | 某科室某天的可约排班，含医生姓名/职称/擅长/余量 |
| GET | `/clinic/appointments?patientId=&limit=` | 我的预约列表 |
| GET | `/clinic/appointments/{apptNo}` | 预约详情 |
| POST | `/clinic/appointments/{apptNo}/pay` | 模拟支付：`PENDING_PAY → BOOKED` |
| POST | `/clinic/appointments/{apptNo}/refund` | 退号：`BOOKED → REFUNDED`，号源归还 |
| POST | `/clinic/appointments/{apptNo}/complete` | 就诊完成 |
| POST | `/clinic/appointments/{apptNo}/no-show` | 标记失约（不归还号源）|

状态类操作的响应统一是 `{ok, result, apptNo, message, status, statusLabel}`。
`result` 可能是 `OK / NOT_FOUND / WRONG_STATE / NOT_REFUNDABLE`。

> `WRONG_STATE` 有个容易忽略的成因：**支付时正好被超时任务抢先释放了**。
> 后端用带旧状态条件的 UPDATE 让数据库裁决胜负，所以前端拿到 `WRONG_STATE`
> 时应该刷新列表看真实状态，而不是简单报"支付失败"。

### 运营端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/dashboard` | 看板聚合：号池、放号进度、预约分布、性能、风控、配置、最近变更 |
| GET | `/admin/schedules` | 排班列表（运营视角，含已放/已约）|
| POST | `/admin/schedules?doctorId=&visitDate=&period=&slotType=&totalSlots=&feeCents=` | 建排班，**返回 `scheduleId`** |
| POST | `/admin/schedules/{id}/open` | 开始放号（节奏由 `release.spreadSeconds` 决定）|
| POST | `/admin/schedules/{id}/close` | 停止放号，剩余号不再放出 |
| GET | `/admin/schedules/{id}/progress` | 放号进度 |
| GET | `/admin/risk/events?limit=` | 风控命中记录 |
| GET | `/admin/patients/blocked` | 失约黑名单 |
| POST | `/admin/patients/{id}/unblock` | 解除拉黑（申诉通道）|

### 控制面

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/control/metrics` | 实时指标快照 |
| GET | `/control/config` | 当前热参数 |
| POST | `/control/config?param=&value=&reason=` | 改参数，**会过护栏** |
| POST | `/control/config/rollback` | 回滚最后一次变更 |
| GET | `/control/audit?limit=` | 变更审计 |

> **改参数的响应必须回显护栏的处置**，不能假设改成功了。
> 护栏可能钳制到区间边界、也可能因冷却期未过直接驳回。
> 响应里 `accepted=false` 时要把 `guardNote` 展示给运营，否则他会以为改生效了。

---

## 三、重写时的技术选型建议

如果要做成正式前端，建议 **Vue 3 + Vite + Element Plus**：国内后端岗最常见的组合，
组件齐全，简历上也说得清。需要注意的三点：

1. **不要引入状态管理库**。这个项目的状态很浅（当前科室、当前日期、预约列表），
   Pinia 只会增加理解成本。
2. **倒计时做成组件但 timer 放在外面**。每个组件一个 `setInterval` 在列表长了之后很浪费，
   用一个全局 timer 广播当前时间。
3. **运营端和患者端的设计取向不同**。患者端是"读一段流程"（引导性强、一次只做一件事），
   运营端是"一眼扫状态"（先给结论、异常项高亮、自动刷新）。用同一套组件硬套会两边都不好用。

---

## 四、已验证的完整旅程

参考实现已实测走通（号池 1003，50 个号）：

```
抢号        code=200
我的预约    A1003-1  就诊时间 08:00  序号 1  待支付
支付        PENDING_PAY → BOOKED
退号        BOOKED → REFUNDED
号池余量    回到 50（桶 31 + 实例持有 19）—— 号源守恒成立
```
