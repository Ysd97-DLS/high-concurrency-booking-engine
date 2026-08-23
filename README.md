# FlashPilot — 高并发预约挂号系统

**面向医院放号洪峰的预约挂号系统。** 在模拟数万用户同秒争抢热门号源（压测号池 6 万～80 万）下保证**不超卖、不少卖**；其方法为将业务约束固化为运行时可计算的不变量，在压测与故障注入中持续自证。

放号是秒杀的一种，所以下面所有关于「秒杀路径」的讨论都成立；但这个系统的业务对象自始至终是**号源**（`clinic` 包、排班号、凭证号），仓库名 `booking-engine` 即由此而来。

架构上还有一条不常见的分工：**把 LLM 放在秒级的控制面，而不是毫秒级的请求路径上。**

- **数据面**（`dataplane`）：请求路径。三层库存 + Lua 原子扣减 + 本地号段，绝大多数请求只做一次内存 CAS，**没有任何 LLM 调用，也没有任何分布式锁**。
- **控制面**（`controlplane`）：反馈回路。L0 规则控制器（AIMD）秒级止损兜底，L1 LLM Agent 做跨指标归因与参数提案，配套护栏六件套。
- **验证层**（`verify`）：把「我保证不超卖」变成五条可执行的断言，并在故障注入下反复跑。

> LLM 一次调用是秒级，放号请求预算是毫秒级，差三个数量级 —— 所以它只能待在本来就是秒级的反馈回路里。这是整个项目最值得聊的架构取舍。

> **关于提交历史**：开发从 2026.06 持续到 2026.08，期间的工作树历史杂乱（大量 WIP 与实验分支）。2026.08 整理成按主题划分的提交后才公开，所以提交时间集中在几天里，而项目本身不是（提交数不写死在这里——每补一次提交它就过期一次，`git log --oneline` 才是准的）。文中每个数字都能在 `docs/` 的实验报告或 `logs/` 里找到出处。

完整设计与六周排期见项目设计文档（架构图、创新点、压测方案、简历写法、25 道面试题）。

---

## 一、快速开始

### 0. 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 本机是 24，源码级别锁在 17（`maven.compiler.release=17`） |
| Maven | 3.9+ | 已配阿里云镜像 |
| Docker Desktop | 任意 | **必须先手动启动**（启动 Docker 服务需要管理员权限） |
| Node | 20+ | **只有改前端时才需要**。前端构建产物已纳入版本控制，所以只想跑起来看看的话不用装 |

### 1. 起基础设施

```powershell
cd flashpilot
docker compose up -d              # redis + mysql + rocketmq（namesrv + broker）
docker compose ps                 # 等 redis / mysql 两个 healthy 就能起应用
```

RocketMQ 起不来也不挡路：`rocketmq.name-server` 连不上时消息层自动降级
（支付超时退回纯定时扫描，状态变更/创建事件不发），抢号主链路不受影响 —— 详见第八节。

建表 SQL 挂在 MySQL 容器的初始化目录里，**首次启动自动执行**。改了 `sql/01-schema.sql` 要 `docker compose down -v` 清掉数据卷才会重新跑。

**后加的迁移不会自动执行。** 初始化目录只在数据卷为空时跑一次，所以一个已经在用的数据库
拿不到后来新增的 `sql/05` 和 `sql/06`：

```powershell
# 逐个手工执行（幂等性各自不同，出错先看是不是已经跑过了）
Get-Content sql\05-reconcile-log.sql | docker exec -i fp-mysql sh -c 'MYSQL_PWD=flashpilot mysql -uroot -D flashpilot'
Get-Content sql\06-noshow-index.sql  | docker exec -i fp-mysql sh -c 'MYSQL_PWD=flashpilot mysql -uroot -D flashpilot'
```

漏跑的表现刻意做成了「能看见」而不是「报错崩掉」：
`05`（对账留档表）漏了的话运营看板会显示一条黄色提示，其余部分照常工作；
`06`（失约扫描的索引）漏了的话扫描仍然正确，只是走全表扫 —— 表大了会慢。

想要 Prometheus + Grafana：

```powershell
docker compose --profile obs up -d
# Grafana     http://localhost:3000   匿名可进，dashboard 自动加载
# Prometheus  http://localhost:9090
```

打开 Grafana 就有 **FlashPilot 控制面** 这个 dashboard（provisioning 自动导入，不用手动建）。
它最上面三张图是刻意分开画的：

```
①  消费积压        stream_pending
②  放行阈值        control_limit_qps      ← L0 AIMD 的反应
③  抢号 P99        seckill_latency_seconds
```

**为什么不把它们叠成一张双轴图**：积压是万级、放行是万级 req/s、P99 是毫秒，
三个量纲放一张图必须配两三个 Y 轴，而**双轴的刻度对齐是任意的，会凭空造出一个数据里没有的相关性**。
分开画 + 共享时间光标（`graphTooltip: 1`），因果关系照样读得出来，而且不骗人。

实测一轮 60 秒压测下这三张图上的读数：

| 时刻 | 积压 | 放行阈值 | P99 |
|---|---|---|---|
| 积压起点 | 35,235 | 14,000 | 54.5 ms |
| 积压峰值（10 秒后） | 61,944 | **6,860** | 33.6 ms |
| 压测结束 | 0 | 21,825 | 0 ms |

**放行阈值降 51% 是对积压上涨的反应，P99 随之回落** —— 这就是 AIMD 闭环，
把光标在三张图上横着扫一遍就能看出来。

配色不是随手挑的：多序列面板都跑过色盲可分辨性校验。
第一版按语义配色（成交=绿、限流拒绝=橙）**没通过** ——
绿 `#008300` 和橙 `#d95926` 相邻时，红绿色盲看它们的差异只有 ΔE 2.7，
等于两条线并在一起分不开。最终的堆叠次序 orange→blue→yellow→violet→red
最差相邻对 ΔE 19.5。

### 2. 起应用

```powershell
mvn spring-boot:run
```

**要跑压测或实验脚本的话，先设这个环境变量再启动：**

```powershell
$env:PATIENT_TOKEN_SECRET = "bench-secret"
mvn spring-boot:run
```

抢号接口的患者身份来自 HMAC 签名令牌（不再是 `?holderId=` 参数，理由见第九节「安全边界」），
压测端要拿同一个密钥自己签，所以两边必须一致。**顺序很重要**：服务端启动时
如果没读到这个变量，它会**随机生成**一个密钥（启动日志有红字警告），那样压测端签的令牌永远对不上。
只是打开网页点一点的话不用管——前端会自己调 `/clinic/identify` 换令牌。

看到 `热配置就绪` 和 `Stream 消费者启动` 就算好了。启动日志里还会有一行
`调度池容量检查通过：15 个定时任务 / 18 个线程（余 3）`——
那是一条会自己喊的不变量，红字就说明池子配小了 —— 定时任务数超过线程数时，
被饿死的任务不会报错，只会静默停止反应，所以这个数字由机器数而不是靠注释记。

想打成 jar 跑：

```powershell
mvn clean package -DskipTests
java -jar target\flashpilot-0.1.0.jar
```

**`mvn package` 不需要 Node。** 前端构建产物（682K）直接纳入版本控制，
vite 的 `outDir` 就指向 `src/main/resources/static/app`，所以打出来的 jar 里前端是齐的。
提交构建产物通常算坏实践，但这个项目的首要用途是「别人能不能跑起来看一眼」——
为此先装 Node、跑 `npm install` 拉几百 MB 依赖，门槛太高了。

改了前端代码之后要重新构建，两种方式等价：

```powershell
cd frontend; npm run build     # 直接跑
mvn -Pfrontend package         # 或者让 maven 顺手带上（profile 默认不激活）
```

**jar 被运行中的 JVM 锁着时 `mvn clean` 会失败**，而且失败之后 `target/` 处于不一致状态、
接着跑 `package` 会打出一个能启动失败的坏 jar（表现是 `ClassNotFoundException`）。
所以顺序永远是：停进程 → 确认没有残留 → `clean package`。

### 3. 灌演示数据，打开网页

```powershell
.\scripts\seed-demo.ps1
```

然后打开 **http://localhost:8090** —— 挂号、支付、退号、运营看板都在里面（Vue 3 应用已经打进 jar，不用单独起前端）。

这个脚本做两件事：灌未来 7 天的排班（`sql/04-demo-data.sql`），再逐个调 `POST /admin/schedules/{id}/open` 把号推进 Redis。

**第二步不能省，也不能用 SQL 代替。** 放号是「先在 MySQL 预留进度、再推进 Redis 桶」的两步操作，只灌 SQL 等于只做了前一半：数据库说号放完了、Redis 桶里是空的，于是运营看板显示放号 100%，患者却全部收到「号源已满」。这类「看起来成功」的状态是这个项目里最反复出现的坑。

> 想改前端就 `cd frontend && npm run dev`（热更新，接口自动代理到 8090）。详见 [frontend/README.md](frontend/README.md)。

### 4. 跑测试

```powershell
mvn test              # 后端 215 个单元测试，几秒跑完，不需要 docker
mvn verify            # 单元 + 集成（Lua 并发不变量，需要 docker compose up -d）
cd frontend; npm test # 前端 100 个
```

315 个测试，**刻意只覆盖两类东西**：纯逻辑单元，以及「坏了不会报错」的不变量。

不追求覆盖率，因为这个项目 78 个缺陷里超过三分之一属于「系统看起来正常但已停止工作」——
那类问题不是靠多测几个分支发现的，而是靠把**不变量**写成断言。举几个例子：

| 测什么 | 它守的是什么 |
|---|---|
| `CountMinSketchTest` | 高负载下判据不能退化成对所有人恒真（缺陷 ⑳ 的后果是 52 万请求被误丢） |
| `ApptStatusTest` | 「占号 / 已归还」必须覆盖全部状态且不重叠——等式③ 的配平直接依赖它 |
| `RiskControlServiceTest` | 阈值必须永远高于噪声底，且只能被抬高不能被压低 |
| `GuardDeciderTest` | 护栏是**唯一允许 LLM 改生产参数**的地方：该挡的必须挡住，人的明确指令绝不能被挡 |
| `McpProtocolTest` | MCP 的两条错误通道不能混：未知工具是协议错误，执行失败是 `isError` 结果 |
| `dates.spec.js` | 日期必须按**本地**时区算——放号在 6:00/7:00，而 `toISOString()` 在清晨会差一天 |
| `useCountdown.spec.js` | 倒计时只能用 epoch 毫秒——不带时区的字符串会被按客户端时区误读，10 分钟显示成 490 分钟 |
| `TailModeGateTest` | 尾部模式必须能退出——分批放号会把库存加回来，单向闩锁会让号段优化永久失效 |
| `ReconcileDeciderTest` | 凡是「不该动手」的情况一次都不能动手——误补一次就是凭空造号 |

> `CountMinSketchTest` 写完当场推翻了我前一小时的修复：我以为「加宽草图 + 减噪声底」
> 就够了，测试直接测了我没验证过的组合（**阈值 3 + 高负载**），发现普通患者的估计值
> 仍然是 4 > 3。这是这个项目里**第一个由测试而不是由观察发现的缺陷**，
> 也是"为什么值得写测试"最直接的证据。

### 5. 跑第一轮实验

```powershell
mvn compile                       # 压测器需要 target/classes
.\scripts\run-experiment.ps1
```

这个脚本会走完 **重置 → 压测 → 等消费追平 → 一致性校验 → 打印报告** 全流程，最后给你一行可以直接抄进实验表格的结果。

---

## 二、目录结构

```
flashpilot/
├── docker-compose.yaml           redis + mysql + rocketmq (+obs profile: prometheus/grafana)
├── docker-compose.ha.yaml        主从+哨兵，P6 故障注入实验用
├── sql/
│   ├── 01-schema.sql             引擎表（号池、事件、审计）
│   ├── 02-clinic-schema.sql      挂号域 6 张表 + 科室医生种子
│   ├── 03-release-progress.sql   released_slots 列（放号进度）
│   ├── 04-demo-data.sql          未来 7 天的演示排班，可反复执行
│   ├── 05-reconcile-log.sql      对账补偿留档（改动号源账目的动作必须可审计）
│   └── 06-noshow-index.sql       失约扫描索引（漏跑只是慢，不会错）
├── deploy/                       prometheus / grafana / rocketmq broker 配置
├── scripts/
│   ├── seed-demo.ps1             灌演示数据 + 走真实放号链路（第一次跑先用这个）
│   ├── run-experiment.ps1        一键跑完整实验（压测最常用）
│   ├── soak-expiry.ps1           超时释放的量级验证（第七个画像）
│   ├── chaos.ps1                 故障注入
│   ├── start-ha.ps1              起主从+哨兵
│   ├── gen-tokens.ps1            预生成患者令牌池（wrk 压测用）
│   └── wrk-seckill.lua           wrk 脚本（以后在 WSL/Linux 用）
├── frontend/                     Vue 3 + Vite 前端，构建产物打进 jar
│   └── src/{api,composables,constants,views}
└── src/main/
    ├── resources/
    │   ├── lua/                  11 个 Lua 脚本，数据面的核心都在这
    │   ├── mapper/               8 个 MyBatis XML（54 条 SQL，见第七节）
    │   └── static/app/           前端构建产物（刻意纳入版本控制，理由见快速开始）
    └── java/com/flashpilot/
        ├── config/               配置、Lua 加载、实例身份、AdminGuard 准入
        ├── dataplane/            **引擎**：限流 → 判重 → 库存 → 发事件
        │   ├── stock/            三层库存、桶间借调、号段租约
        │   ├── limit/            进程内令牌桶
        │   ├── dedupe/           一人一单判重（LOCAL / REDIS 可切）
        │   ├── stream/           Stream 生产与消费、pending 抢占
        │   └── order/            事件落库（幂等 + 不超卖兜底）
        ├── clinic/               **业务域**：挂号。引擎不知道自己在卖号还是卖鞋
        │   ├── domain/           六状态机、排班、患者、读模型
        │   ├── auth/             患者身份：HMAC 令牌签发与验签
        │   ├── risk/             三层风控（Count-Min Sketch）+ 慢车道
        │   ├── admin/            排班管理、分批放号
        │   ├── event/            领域事件：状态变更走顺序消息、创建走事务消息 + 乱序检测
        │   ├── expiry/           支付超时（RocketMQ 定时消息主路径 + 扫表兜底）
        │   ├── reconcile/        对账补偿（四道闸门，判据抽成纯函数可单测）
        │   └── api/              患者端接口
        ├── controlplane/         控制面
        │   ├── config/           热配置中心（Hash + Pub/Sub + 兜底轮询）
        │   ├── guard/            护栏六件套（判据抽成 GuardDecider，纯函数 42 个单测）
        │   ├── l0/               AIMD 规则控制器
        │   ├── l1/               LLM Agent + 工具层
        │   ├── api/              控制面接口
        │   └── mcp/              标准 MCP server（JSON-RPC 2.0，协议层纯函数可单测）
        ├── verify/               一致性校验器 + 实验编排
        ├── metrics/              Micrometer 埋点
        └── tools/LoadGenerator   内置压测器（零外部依赖）
```

**引擎和业务域的分界**是这个项目最重要的结构决定：`dataplane/` 只认 `poolId` + `holderId`，不知道自己在卖号还是卖鞋；`clinic/` 提供垂类语义（医生、排班、六状态机、退号规则）。分界线正好落在 `POST /seckill/{poolId}`——抢号走引擎热路径，其余旅程走 `/clinic/**`。详见 [docs/DOMAIN.md](docs/DOMAIN.md)。

---

## 三、接口清单

### 数据面（引擎）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/seckill/{poolId}?deviceId=` | 抢号。**需要 `X-Patient-Token`**（患者身份由服务端签发，不能由客户端声明）。售罄/限流也返回 HTTP 200 + 业务码，避免压测工具统计成 error |
| GET | `/seckill/state/{poolId}` | 库存分布、号段命中率、借调次数、桶倾斜度 |

业务码：`200` 成功 / `4001` 号已售罄 / `4002` 一人一单 / `4030` 风控拉黑 / `4290` 被限流 / `5000` 内部异常。

**所有业务错误都是 HTTP 200 + `code`**，只有传输层错误才是非 200。理由：秒杀场景里「售罄」「限流」「重复」都是高频正常路径，做成 4xx 会让压测工具把它们统计成错误率，也会让浏览器控制台刷一片红。

### 患者端（业务域）

| 方法 | 路径 | 需要令牌 | 说明 |
|---|---|:-:|---|
| POST | `/clinic/identify?patientId=` | — | **签发患者令牌。演示桩**，真实系统这里必须校验密码或短信验证码。按来源 IP 限流（每分钟 20 次） |
| GET | `/clinic/departments` | — | 科室列表 |
| GET | `/clinic/schedules?departmentId=&date=` | — | 某科室某天可约排班（默认查明天） |
| GET | `/clinic/appointments?limit=` | ✓ | 我的预约（带医生/科室，一次 IN 查询避免 N+1）。**`patientId` 参数已移除** |
| GET | `/clinic/appointments/{apptNo}` | ✓ | 预约详情，只能看自己的 |
| POST | `/clinic/appointments/{apptNo}/pay` | ✓ | 模拟支付 → BOOKED |
| POST | `/clinic/appointments/{apptNo}/refund` | ✓ | 退号 → REFUNDED，号源归还号池 |
| GET | `/clinic/server-time` | — | 服务端时间，前端倒计时校准用 |

带 ✓ 的接口要在请求头里带 `X-Patient-Token`，并且会校验**这张单是不是你的**。不是自己的单一律返回「不存在」而不是「无权限」——两者区分开的话，凭证号（格式 `A{poolId}-{seq}`，完全可枚举）就能被批量试探出来。

`complete` / `no-show` 从这里搬到了 `/admin` 下：它们是**院方**操作，患者对自己的单也不该有这两个权限。

### 运营端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/dashboard?scheduleId=` | 看板聚合：号池 + 放号进度 + 状态分布 + 性能 + 风控 + 热配置 + 最近变更 |
| GET | `/admin/schedules?date=` | 排班列表 |
| POST | `/admin/schedules?doctorId=&visitDate=&totalSlots=&feeCents=…` | 建排班，**返回自增 scheduleId** |
| POST | `/admin/schedules/{id}/open` | 开始放号（按 `release.spreadSeconds` 决定一次性还是分批） |
| POST | `/admin/schedules/{id}/close` | 停止放号，剩余号不再放出 |
| GET | `/admin/schedules/{id}/progress` | 放号进度 |
| GET | `/admin/risk/events?limit=` | 风控命中明细 |
| GET | `/admin/patients/blocked` | 被限制预约的患者 |
| POST | `/admin/reconcile/run?dryRun=` | 手工跑一次对账补偿，**默认 dryRun=true** |
| GET | `/admin/reconcile/history?limit=` | 对账留档（只记真动手 / 拒绝动手 / 预演）|
| POST | `/admin/patients/{id}/unblock` | 人工解除限制（申诉通道） |
| POST | `/admin/appointments/{apptNo}/complete` | 登记就诊完成 → COMPLETED |
| POST | `/admin/appointments/{apptNo}/no-show` | 登记失约 → NO_SHOW（累计 3 次限制预约 30 天）。日常由 `NoShowScanTask` 自动扫，这个接口给院方手工纠正用 |

> `/admin/*`、`/verify/*`、`/control/*`、`/mcp` 全部由 `AdminGuard` 守着：**来自本机 || 令牌正确**。本地开发和压测脚本因此零摩擦，从外部访问要在 `X-Admin-Token` 里带上 `ADMIN_TOKEN`。详见下面的「安全边界」。

### 控制面

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/control/metrics` | 当前指标快照（L0 和 L1 读的就是这个） |
| GET | `/control/config` | 热参数当前值与允许区间 |
| POST | `/control/config?param=&value=&reason=&dryRun=` | 人工改参数，同样过护栏 |
| POST | `/control/config/rollback` | 一键回滚最后一次生效的变更 |
| GET | `/control/audit?limit=` | 变更审计（**包含被驳回的**） |
| GET | `/control/agent/timeline` | Agent 决策时间线 |
| POST | `/control/agent/tick?dryRun=` | 手工触发一次 Agent 决策 |
| **POST** | **`/mcp`** | **标准 MCP server（JSON-RPC 2.0）**：`initialize` / `ping` / `tools/list` / `tools/call`。任意 MCP 客户端可直接接入 |
| GET | `/mcp/tools` | ~~工具清单~~ 已废弃，自定义形态，标准客户端发现不了 |
| POST | `/mcp/call` | ~~调用工具~~ 已废弃，用 `POST /mcp` 的 `tools/call` |

### 验证层

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/verify/preheat?poolId=&totalStock=&buckets=` | 重置到干净起点，**每轮压测前必调** |
| GET | `/verify/check` | 五条一致性等式 |
| GET | `/verify/history?limit=` | 历史校验报告 |

> `preheat` 不只是灌库存：它同时划定了「累计量」等式的共同起点。等式⑤ 的累计量那一组比较的是 MySQL 里的落库预约数和 Redis 里的消费入库计数器（`fp:stat:consumed`）——**两个存储各自被清空的时机完全无关**，所以只有被 preheat 同时归零过才可比。没走 preheat 时这一组会显示「口径不可比，本组不判定」而不是报不一致——一个平时就是红的校验报告等于没有报告。

---

## 四、三层库存是怎么工作的

```
MySQL 权威总库存 1000
        │  预热时按权重切分
        ▼
Redis 32 个物理桶（前 N 个为「活跃桶」，请求哈希到这 N 个）
        │  批量领号段（默认 20 件一次）
        ▼
各实例本地 AtomicLong  ← 绝大多数请求只碰这里，零网络
```

**Redis 的调用量降到原来的 1/20**（号段大小决定），而失败的请求（售罄、限流、重复）在 LOCAL 判重模式下**完全不碰网络**。成交请求受库存上限约束，总量很小。

### 四个必须理解的设计决策

**① 为什么先扣本地再发事件**

顺序是：本地 `AtomicLong` 预占 → Lua 原子地 `XADD` + 租约扣 1 → 才给用户返回成功。

如果实例在第一步之后、第二步之前崩了：内存里的预占随进程消失，而 Redis 里租约仍记着这一件，回收任务会把它还回桶 —— **不丢不多**。反过来「先发事件再扣本地」就会超卖。

**② 物理桶固定 32，热调的是「活跃桶数」**

直觉实现是「物理桶数 = 配置值」，但那样把桶数从 8 调到 4 的瞬间，桶 4~7 里的库存就没有任何请求会碰到了 —— **等于凭空少卖**。

现在的实现里借调循环始终扫描全部 32 个物理桶，高位桶里的存货一定会被捞出来，所以调小活跃桶数是随时安全的。

**③ 一人一单的判重位置是可切换的，这决定了本地号段到底省不省 RTT**

| 模式 | 热路径 Redis 调用 | 前提 |
|---|---|---|
| `LOCAL`（默认） | **0 次** | 网关按 userId 一致性哈希，同一用户始终落到同一实例 |
| `REDIS` | 1 次（Bitmap `SETBIT`） | 无需粘性路由 |

这是本项目相对原始设计的一处修正：**如果判重走 Redis，那本地号段省下来的那次 RTT 就白省了**。两种模式的对比本身就是一组值得做的实验（`flashpilot.dedupe.mode`）。

MySQL 的 `uk_user_item` 唯一索引在两种模式下都是最终保证。

**④ 尾部单件模式**

全局剩余低于 `stock.tail` 时关闭号段，退化为单件直扣。否则活动尾声会出现「全局还剩 3 件但分别卡在 3 个实例手里，用户却打到了第 4 个实例」的少卖。

---

## 五、控制面

### L0：AIMD 规则控制器

思路直接来自 TCP 拥塞控制：P99 超 SLO 就**乘性降速**，连续健康且确实有误拒才**加性上调**。

纯代码、无外部依赖、决策微秒级，所以**永远在线**。Agent 挂掉时系统就退化成只有这一层。

它的短板也要说清楚：它只会「P99 涨了就降速」，答不了「P99 **为什么**涨」。

### L1：LLM Agent

**事件驱动**：只有指标越界才唤醒模型（`trigger-*` 阈值），健康时根本不调用，所以 token 成本几乎为零。

**结构化输出**：强制走 tool call，参数有 schema。不解析自由文本 —— 那种做法模型措辞一变就崩。

**观察窗口**：一次变更后必须等满 `observe-window-ms` 才允许下一次决策，这是防震荡的第一手段。

### L1 的真实决策数据（接上 DeepSeek 后实测）

两条真实决策合起来，讲完了 L1 的价值和它的边界：

**第一条被过期保护拦下了。** 触发是「P99=79.7ms 超阈值」，L1 诊断 66 秒后提案「limit.qps 砍到 3000」——
而这 66 秒里 **L0 已经把 P99 降到了 3.3ms**。执行那个提案等于把一个已经健康的系统的闸门再砍 78%，
纯粹的伤害。所以决策回来时会重新取快照，触发条件已消失就放弃执行（`STALE_DROPPED`）——
放弃不是失败，它恰恰说明系统自己恢复了。

**第二条它主动选择不动。** 触发是「误拒率 23%」，它的归因是算出来的：
入站 29.98k req/s、生效限流 23081，`(29979-23081)/29979 ≈ 23%` 与误拒率精确吻合；
逐项排除下游瓶颈之后，它**从审计记录里发现 L0 刚刚已经把限流提到 32314**，
于是判断「变更尚在观察窗口内，叠加调参易触发冷却期」——不做额外调整。
**「知道自己刚做过什么并因此收手」是纯规则控制器做不到的。**

由此得到整个项目最重要的实测结论：

> **LLM 单次决策 10–66 秒，比控制面的观察窗口还长。这决定了它做不了秒级执行器——
> L0 规则层不是 L1 的降级方案，它是主力；L1 是它的解释器和调参建议者。**
> 开篇那句「LLM 只进秒级控制面」还不够：秒级也分档，要看实测延迟落在哪一档。

### 护栏六件套

1. **白名单** —— 只能改 `ConfigParam` 里列的参数
2. **区间钳制** —— 越界钳到边界（方向对就让它生效一部分）
3. **幅度限制** —— 单次不超过当前值的 `max-change-ratio`
4. **冷却期** —— 同参数两次变更的最小间隔；人工变更可绕过
5. **dry-run 预演** —— 校验全过、算出会改成什么值，但不写入（`Verdict.DRY_RUN`）
6. **审计 + 一键回滚** —— 通过和驳回都落表

再加一条兜底：**Agent 不可用时自动退化为纯 L0，功能与安全性不受影响。**它是可选增强，不是关键路径依赖。

### 开启 Agent

支持任何 OpenAI 兼容端点（DeepSeek / Moonshot / 通义 / 本地 vLLM、Ollama）：

```powershell
$env:LLM_API_KEY  = "sk-你的key"
$env:LLM_BASE_URL = "https://api.deepseek.com"
$env:LLM_MODEL    = "deepseek-v4-flash"    # 不设也行，这就是默认值
# application.yml 里把 flashpilot.control.agent.enabled 改成 true
mvn spring-boot:run
```

模型名这类「外部依赖的名字」会随供应商更新而过时（配置里原来写的 `deepseek-chat` 已经不存在了），
启用后如果什么都不发生，先看日志里的 LLM 调用错误。默认选 flash 而不是 pro 是实测的结论：
两者在这个任务上的提案质量没有实质差别，而 pro 一次决策 41–43 秒、flash 14–16 秒 ——
**对控制面来说延迟就是质量**（详见下面「L1 的真实决策数据」）。

不配 key 也能跑，只是控制面只有 L0 —— 这是设计好的降级路径，不是故障。

---

## 六、实验手册

每轮都用 `run-experiment.ps1`，它保证了可重复性。**基线数据一定要在最开始就拿到**，后面所有「提升了多少」都以它为锚。

### 先看这一个数：有货时长

报告里的**有货时长**（采样中「桶余量 + 实例持有 > 0」的占比）比吞吐和 P99 都该先看。
号卖光之后的请求全是稳定返回「号源已满」——吞吐很高、延迟很低、五条等式全过，
**看起来是一轮很漂亮的压测，实际上什么都没测**。

这不是假设。故障注入的时机原来写死成「压测过半」，而库存 60000 在第 6.5 秒就被抽干，
于是冻结 MySQL 打在了一个无号可卖的系统上，报告照样给出「40 秒、39194 req/s、全过」。
现在注入改成**探测驱动**（等系统「已经在卖」且「仍有货」才注入，`-ChaosAfter` 控制下限），
并在有货时长低于 30% 时红字警告。

**库存要按「成交速率 × 时长」估**，宁可给多。constant 画像约 2000–4000 成交/秒。

| 画像 | 命令 | 观察什么 |
|---|---|---|
| **P1 恒定高压** | `-Concurrency 200 -Duration 30 -Stock 100000` | 基线 P99、号段命中率；**刻意卖到售罄**，这是唯一能判定等式②「不少卖」的一轮 |
| **P2 脉冲** | `-Shape burst -Concurrency 400 -Duration 20 -Stock 300000` | 误拒率、控制面收敛时间；P99 应该是六个里最高的 |
| **P3 热点倾斜** | `-Shape skew -Concurrency 200 -Duration 25 -Stock 80000` | **桶倾斜度、借调次数**——借调只在这个画像下被真正压出来 |
| **P4 慢依赖** | `-Duration 40 -Stock 400000 -Chaos mysql-pause -ChaosSeconds 10` | 积压曲线、**AIMD 是否主动降速**；下单路径应该完全不受影响 |
| **P5 节点故障** | 起第二实例后 `-Duration 90 -Stock 800000 -Chaos kill-app -ChaosPort 8091 -ChaosAfter 15 -LoadUrl http://127.0.0.1:8091` | **租约回收**、少卖率；`-ChaosAfter 15` 让被杀实例先领到号段，否则等于没测 |
| **P6 主从切换** | `scripts\start-ha.ps1` 后以 ha profile 起应用，再 `-Duration 90 -Stock 500000 -Chaos redis-failover -ChaosAfter 15` | 异步复制丢写对库存的影响；**等式③④ 按预期失败才是好结果** |

命令都省略了前缀 `.\scripts\run-experiment.ps1`。

### 第七个画像：超时释放的量级验证

六个画像都只跑 20-90 秒，**而支付时限是 10 分钟** —— 于是超时释放这条路径
（「先改 MySQL 状态、再还 Redis 号源」两步）从来没在几万张单同时到期的量级上跑过，
而它出错的方向是**双重归还 = 超卖**，不是少卖。

```powershell
# 支付时限调成 1 分钟，否则观察窗口内一张单都不会到期
java -jar target\flashpilot-0.1.0.jar --flashpilot.clinic.pay-minutes=1
.\scripts\soak-expiry.ps1 -Stock 60000 -LoadSeconds 30 -WatchMinutes 6
```

它压一轮攒下几万张待支付单，然后**什么都不做只采样**，看号源怎么回来。
三条判据里最该先看的是**桶余量不得超过总号数** —— 那比等式③ 的残差更早、更硬地暴露重复归还。

第一次跑就抓到一个缺陷：`fixedDelay` 的语义是「上一次**结束**后再等 3 秒」，
所以有积压时周期 = 处理耗时 + 空等，实测吞吐只有 117 个/秒
（而「单批 500 / 间隔 3 秒」给人的印象是 167）。
连续 88 批全部触达上限，日志一直在喊「仍有积压」而系统就是不加快。
改成「本批跑满就立刻接着跑下一批」之后：**394 个/秒，6 万张单 152 秒排空，
桶余量峰值精确等于总号数**。

P5 的服务端指标不可用（压的是另一个实例，而控制面读的是本机），脚本会明确标注并指向客户端数字
——不要把那几个 `0.0ms` 抄进实验表格。

多实例（P5 需要）：

```powershell
# 第二实例。jar 被运行中的 JVM 锁着，改完代码要先停进程再 mvn package
$env:INSTANCE_ID="inst-b"; $env:SERVER_PORT="8091"
Start-Process java -ArgumentList "-jar","target\flashpilot-0.1.0.jar" -WindowStyle Hidden
```

**注意 `dedupe.mode` 默认是 `LOCAL`**（判重放在进程内存里），它要求网关按 userId 一致性哈希。
多实例而没有粘性路由时，同一患者会在每个实例各抢到一个号，落库撞唯一索引，
**号从 Redis 扣走却既不落单也不归还**——实测双实例下 20% 的号源就这么没了，
而客户端全部收到「抢号成功」，只有一致性等式能看见。
启动日志和运营看板都会对这个组合打红字告警。

### 五条一致性等式

```
① 不超卖    MySQL 订单数 ≤ 初始库存
② 不少卖    售罄时 MySQL 订单数 == 初始库存
③ 库存守恒  初始库存 == Σ桶剩余 + Σ实例本地持有 + 已发出的成交事件数
④ 链路守恒  已发出事件数 == 已入库 + 重复跳过 + 超卖拦截 + 死信 + 未处理
```

**等式 ③ 最有价值**：它把「库存现在到底在谁手里」变得可观测。少卖的根因（库存卡在宕机实例的本地余量里）会被它直接暴露成一个非零残差，而只检查「有没有超卖」的做法永远发现不了它。

### 压测的诚信底线

所有数字都要连同环境一起写：**压测器和被压服务跑在同一台机器上，会争抢 CPU**。关注同环境下不同方案的**差值**，不要拿绝对值去暗示生产能力。面试官问一句环境就见真章。

---

## 七、持久层：为什么是 MyBatis + XML

领域 CRUD 与动态 SQL 走 **MyBatis**，SQL 全部放 XML 而不是注解。

**为什么迁**（原来是 JdbcTemplate，54 条 SQL 散在 10 个文件里）：

| 迁移前 | 迁移后 |
|---|---|
| 三处手搓 IN 占位符：`String.join(",", nCopies(n,"?"))` + 手工拼参数数组 | `<foreach>` |
| 批量插入靠 `StringBuilder` 拼 VALUES + 一个 `batch.size()*10` 的 `Object[]`，漏一个字段整体错位 | `<foreach>`，字段名是写出来的 |
| 排班列表按 `date == null` 拼两套 SQL、调两个重载 | 一条语句 + `<if>` |
| 建排班要手写 `PreparedStatementCreator` + `GeneratedKeyHolder` + 九个按下标的 `ps.setXxx` | `useGeneratedKeys="true"` |
| 审计表十个列名抄了三遍，每处配一份手写 `RowMapper` | `<sql>` 片段 + `<constructor>` resultMap |
| 风控事件是 `Object[]{patientId, deviceId, level, action, reason}`，靠下标对齐 | 具名 record |

**为什么 XML 不用注解**：这个项目的 SQL 有条件更新、批量插入、多表求差集，注解里写会难以阅读；而 XML 能用 `<sql>` 片段复用列清单。

**架构没变**：`Repository` 保留为门面，业务语义（截断超长文本、把「查不到」翻成 `Optional`、把「affected rows == 1」翻成「这次调用赢了」）留在那一层，Mapper 只忠实反映数据库做了什么。这条边界让迁移的影响面停在 Repository 内部 —— 上层调用点一行都没改。

### 迁移时踩的两个坑（都值得记）

**① `javaType` 必须写 `_long` / `_boolean`，不是 `long` / `boolean`。**
MyBatis 的别名表里 `long` 指 `java.lang.Long`（装箱），`_long` 才是原始类型。record 的构造器参数是原始类型，写错时 MyBatis 去找 `<init>(Long, Long, ...)` 找不到，抛 `NoSuchMethodException` —— 而这个错**要等真的查出数据才出现，启动和编译全是绿的**。

**② `<constructor>` 的参数顺序必须和 record 声明顺序完全一致。**
MyBatis 按位置匹配，顺序错了而类型恰好兼容时**不报错**，只会把值装错位置（`param` 字段里出现 `source` 的内容）。

两个坑是同一个形状：**错误不在编译期暴露，而在运行期以"数据不对"的形式出现** —— 和这个项目里其它静默失败一个性质。

### 验证

迁移后重跑全部验证，结果与迁移前一致：215 个 Java 测试 + 100 个前端测试全过；60000 号池压测**五条一致性等式全对、零超卖零少卖零消失**；延迟 P50 2.17ms / P99 19.42ms（迁移前 2.05 / 16.68，同一水平）。

---

## 八、消息层：RocketMQ 用在哪、为什么不替换 Redis Stream

三条链路、三种消息类型，各占其位 —— **不是为了集齐三件套，而是每条链路的约束恰好只有一种消息能满足**：

| 链路 | 消息类型 | 为什么是它 |
|---|---|---|
| 支付超时释放 | **定时消息**（5.x 任意延迟） | 4.x 只有 18 个固定延迟级别，而支付时限可配（压测调到 1 分钟），固定级别卡不住 |
| 预约状态变更 | **顺序消息**（凭证号做 sharding key） | 同一张单的 PENDING_PAY → BOOKED → REFUNDED 有因果；乱序时下游会得出「号先退了又被约上」这种从没发生过的结论 |
| 预约已创建 | **事务消息** | 三种类型互斥，定时/顺序已各占一条链路；创建事件是每张单的第一个事件，天然无前序可乱，是事务消息唯一的落点 |

### 为什么不替换 Redis Stream（原计划，查证后否掉）

`XADD` 写在 `sell_one.lua` 里，**和扣租约是同一条 Lua 的原子操作**。拆成「Lua 扣库存 + 另发 MQ」就是双写问题：扣了库存消息没发 = 少卖，消息发了库存没扣 = 超卖。真正不能丢消息的地方反而用不了事务消息 —— 那一步的原子性只能靠 Redis 内部保证。所以 RocketMQ 承接的是 **Stream 做不了的事**，不是替换它。

### 每条链路的设计要点（都实测过）

**定时消息**：主路径 = 消息到期精确触发（无空扫），兜底 = 原来的定时扫表（消息丢了、broker 挂了时补上）。因为有完备但慢的兜底，主路径只需要「快且大概率对」，投递失败也不重试。实测把扫描间隔调到 10 分钟后，单子仍在 deadline **当秒**被释放（`cancelled_at == pay_deadline`）—— 证明是消息干的，不是扫描。

**顺序消息**：`syncSendOrderly` + 凭证号 sharding key（同一张单进同一队列），消费端 `ORDERLY`（队列内串行）。**sharding key 用凭证号而不用排班号**：后者会把同池几万张单压进一个队列，并发度掉到 1，而顺序要求本来就只存在于单张单内部。两半缺一不可、缺任何一半都不报错只静默乱序，所以消费端加了乱序检测：上一条事件的终点必须等于这一条的起点。实测 15 张单跑完整状态链：发布 30 / 消费 30 / 乱序 0。

**事务消息**：本地事务 = 插入预约，回查 = 按 `appt_no`（唯一索引）查库。本地事务异常时**刻意返回 UNKNOWN 而非 ROLLBACK** —— 异常可能发生在插入已提交之后，ROLLBACK 会让消息永远丢掉而库里其实有单，那正是回查存在的意义。实测 6 万号池压测：事务提交全部成功、回滚 0、broker 回查 0、五条等式全对；代价是 P99 19.4 → 22.3 ms（多一次半消息往返）。

### 风控事件走 MQ：一次自我纠错

第一版的反对理由是「风控在 3 万 req/s 的热路径上，加 broker 往返等于把省下的 RTT 还回去」—— **这句话把事件的“产生”和“投递”混为一谈**：热路径只做 `queue.add`（纯内存），落库本来就是定时任务攒批做的，把 flush 的目标从 MySQL 换成 broker，热路径一行不用改。

给「队列满丢弃」加计数后立刻抓到一个真瓶颈：flush 每 2 秒最多取 500 条 = 上限 250 条/秒，而压测下风控命中每秒几千条 —— 8 秒丢了 2679 条。**换 MQ 解决不了它**：换目标解决的是「下游慢时不回压」，这里是「上游取得太慢」，两个问题长得像、修法完全不同。改成每秒一轮、每轮最多 10 批 × 2000 条后，同条件 24801 条命中丢弃 0。

### 降级路径

`rocketmq.name-server` 连不上时整个消息层自动降级：支付超时退回纯定时扫描、领域事件不发，**抢号主链路不受影响**。这里踩过两个 Spring 的坑：`@ConditionalOnBean` 挂在普通 `@Component` 上不可靠（条件在组件扫描阶段评估，而 `RocketMQTemplate` 来自自动配置、那时还不存在）；`ObjectProvider.getIfAvailable()` 只在 bean 不存在时返回 null，**bean 存在但初始化失败时会把异常向上抛** —— 一个可选组件的配置错误不该阻止应用启动。

---

## 九、安全边界

这一节是一次自查的结果。它值得单独写，因为**里面每一条都是实测复现的，不是「理论上可能」**——而其中最严重的两个不是配置疏漏，是设计缺陷：加一层登录也修不掉。

### 修掉的

**① 越权操作预约单（IDOR）**

凭证号格式是 `A{poolId}-{seq}`，完全可枚举，而 `refund` / `no-show` 当时不校验调用者：

```
POST /clinic/appointments/A20006-37/refund
  → {"ok":true,"result":"OK","status":"REFUNDED"}          退掉了别人的号
POST /clinic/appointments/A20011-3/no-show   ×3
  → 患者 8801 的 no_show_count 0 → 1 → 2 → 3               被禁约 30 天
```

**为什么说它不是「忘了加登录」**：就算前面挂上完整的认证，只要业务方法的入参里只有 `apptNo`，患者 A 依然能退患者 B 的号——身份根本没进到判断里。所以修法是把「谁在操作」变成业务方法的必需参数（`AppointmentService.findOwned`），而不是在外面加一层。

顺带两个容易留下的尾巴也一起堵了：响应体的**状态回显**同样要校验归属（否则退号被拒但状态照样泄露，成了一个状态查询接口）；「不是你的」和「不存在」返回**同一个结果**（区分开就能枚举出哪些凭证号真实存在）。

**② `holderId` 由客户端声明 —— 整套风控被一个查询参数绕过**

```
POST /seckill/20016?holderId=999999999
  → {"code":200,"message":"抢购成功，订单正在生成"}
```

冒充只是表面。真正严重的是：**CMS 三层频次判据、设备维度阈值、失约黑名单，全部以 `holderId` 为计数键**。每次请求换一个随机值，每个 ID 的计数恒为 1，永远碰不到任何阈值——号贩子可以匀速刷空整个号池，而运营看板上显示的是「几万个正常患者各抢了一次」。

修法是身份改由服务端签发的 HMAC 令牌携带（`X-Patient-Token`）。

**热路径上的代价实测过**（JIT 预热后 200 万次循环）：

| 指标 | 值 |
|---|---|
| 单次验签 | 0.439 微秒 |
| 单核吞吐 | 2,280,355 次/秒 |
| 占单次请求 P50（2.05 ms）的比例 | **约 0.02%** |

加上它之后重跑 60000 号池的压测：吞吐 35,714 req/s、P99 16.68 ms、**五条等式全对，零超卖零少卖零消失**。这个安全边界基本是白拿的——因为验签是纯 CPU、零 IO，和「本地号段避免 Redis 往返」是同一个考虑。

**③ 运维接口完全无认证**

`/admin/*`、`/verify/*`、`/control/*`、`/mcp` 当时对任何来源开放。其中：

| 接口 | 后果 |
|---|---|
| `POST /verify/preheat` | 里面有 `DELETE FROM t_appointment`——一次请求清空全部预约 |
| `POST /control/config?param=limit.qps&value=1` | 实测护栏把它 clamp 到 100（原值 19825），**护栏限制了幅度但没有阻止未授权修改**，吞吐掉到 1/198 |
| `POST /admin/patients/{id}/unblock` | 放出因失约被禁约的号贩子——风控里唯一「真正拒绝」的手段被撤销 |
| `POST /admin/reconcile/run?dryRun=false` | 直接改号源账目 |
| `POST /control/agent/tick` | 每次调用真实请求一次大模型，**按次计费**，同时是一个经济型 DoS |

修法是 `AdminGuard`：**放行 = 来自本机 || 令牌正确**。本机直接放行是刻意的——能在本机发 HTTP 请求的人同样能读到配置里的令牌，对这种攻击者做 HTTP 认证是自欺欺人，而它换来本地开发和压测脚本零摩擦。**一个让本地开发变麻烦的安全措施，最后会被人用「临时关掉」的方式绕过。**

**「什么算本机」比看起来复杂，两个陷阱都实测过：**

⚠ **反向代理。** 本机跑 Nginx 之类时必须设 `flashpilot.admin.trust-loopback=false`：转发来的请求 `remoteAddr` 都是回环地址，于是「本机放行」把整个互联网都放进来了。这里**刻意不去读 `X-Forwarded-For` 来补救**——那个头客户端可以随便写，拿它做判据等于把钥匙交给攻击者。

⚠ **Docker Desktop（Windows / macOS）。** 容器访问宿主机时流量经过 NAT，**到达应用时源地址是 `127.0.0.1`**，不是容器的 `172.17.x.x`：

```
docker run --rm curlimages/curl -X POST http://host.docker.internal:8090/admin/patients/7001/unblock
  → AdminGuard 日志记录的源地址：127.0.0.1
```

也就是说默认配置下**本机上任何一个容器都能调运维接口**。这台机器上跑着四个容器，其中 Grafana 允许匿名访问——攻击链是存在的。要堵就设 `trust-loopback=false` 配 `ADMIN_TOKEN`。

> 顺带一个方法论教训。验证这个过滤器时，「从容器里打过来被放行了」一度让我以为过滤器压根没生效。实际是**测试方法本身不成立**——那个请求并不是外部请求。换成从本机 WLAN 地址（`192.168.1.3`）打才是真的外部，结果是 403。**安全测试里「攻击成功」和「攻击路径没走通」长得一模一样，必须先证明测试用例真的构造出了目标条件。**

实测的准入矩阵：

| 来源 | `trust-loopback` | 令牌 | 结果 |
|---|:-:|---|:-:|
| `127.0.0.1` | true | 无 | 200 |
| `192.168.1.3`（真实外部） | true | 无 | **403** |
| 容器 → `host.docker.internal` | true | 无 | 200 ⚠ 源地址被 NAT 成回环 |
| `127.0.0.1` | false | 无 | **403** |
| `127.0.0.1` | false | 错 | **403** |
| `127.0.0.1` | false | 对 | 200 |
| `192.168.1.3` | false | 对 | 200 |

**④ 基础设施端口暴露在 0.0.0.0**

`docker-compose.yaml` 里 `"6379:6379"` 这种短写法**默认绑 0.0.0.0**，同网段任何机器都能直连；而 Redis 没有 `requirepass`、MySQL 的 root 密码是 `flashpilot`（仓库公开，等于没有密码）、Grafana 是 `admin/admin`。全部改成 `"127.0.0.1:6379:6379"`。

选「只对本机开放」而不是「加密码」，是因为加密码要改配置、改脚本、改文档，而这里真正需要的只是别暴露出去。

### 确认安全的

**没有 SQL 注入。**全部走参数化查询；动态 `IN` 子句用 `nCopies(n, "?")` 生成占位符，参数始终是绑定的。grep 出来的可疑拼接全部是 `r.put("message", ... + var)`——面向用户的文案，不是 SQL。

### 刻意没做的

- **不上 Spring Security + OAuth。** 它会带来整套过滤器链、默认的 CSRF 与登录页行为，而这个项目要证明的是并发正确性与控制面自治。一个 60 行的过滤器解决了同样的问题，且没有隐式行为。
- **不给密钥硬编码默认值。** `PATIENT_TOKEN_SECRET` 为空时随机生成并打红字警告。给默认值会让所有部署共享同一个签名密钥，任何人都能替任何人签发身份——**比不做认证更糟，因为它看起来是安全的**。
- **不开「压测模式跳过校验」的后门。** 那种开关最终一定会在某个环境里忘记关掉，而它长得就像一个正常配置项。压测端改成拿同一个密钥自己签令牌（`scripts/lib/PatientToken.ps1`、`LoadGenerator.preflight`），这也是真实压测的做法（预认证令牌池）。
- **应用自身不绑 127.0.0.1。** 看起来更安全，实际会静默弄坏可观测性：Prometheus 跑在容器里，通过 `host.docker.internal:8090` 抓宿主机上的应用，绑 loopback 之后抓取失败、Grafana 全部面板变空白——而这个项目的核心结论全靠那些面板。「看起来是安全加固、实际弄坏了项目要证明的东西」是这次自查里最该避免的一类改动。

### 底线声明

**这是一个演示项目，没有真实的用户体系，不要暴露到公网。** `/clinic/identify` 不校验凭据；患者令牌没有有效期也不绑设备。这次修复解决的是**结构问题**——身份由服务端签发、操作校验归属、危险接口有准入边界——把签发接口换成真实的密码/短信校验，下游代码一行都不用改。

### 压测与实验脚本的变化

身份改成签名令牌之后，压测端必须用同一个密钥：

```powershell
# 两边都要设，而且要先设再启动服务端
$env:PATIENT_TOKEN_SECRET = "bench-secret"
mvn spring-boot:run

# 另开一个窗口，同样先设
$env:PATIENT_TOKEN_SECRET = "bench-secret"
./scripts/run-experiment.ps1

# wrk 的 Lua 里没有 HMAC，改成预生成令牌池轮着用
./scripts/gen-tokens.ps1 -Count 200000 -OutFile bench-tokens.txt
wrk -t8 -c400 -d30s -s scripts/wrk-seckill.lua http://127.0.0.1:8090
```

`LoadGenerator` 和 wrk 脚本现在都有**开压前自检**：第一发不是预期结果就退出。这道自检是被一个真实的坑逼出来的——`wrk-seckill.lua` 拼的参数名是 `userId` 而接口收 `holderId`，于是每一发都是 400，而压测**照样跑完、照样给出延迟数字和报告**，那些数字描述的是「400 有多快」。它一直没被发现，因为它从来没在 Linux 上真跑过。

> 压测脚本失败的方式不是报错，是给你一份好看的假数据。

---

## 十、已知缺陷与后续计划

主动写出缺陷是成熟度信号，也是面试时的加分项。

| 缺陷 | 影响 | 计划 |
|---|---|---|
| MCP 只有 Streamable HTTP 传输，没有 stdio | Claude Desktop 这类本地宿主接不上 | 难点不在协议而在 **stdout 被日志污染**：stdio 下 stdout 就是协议信道，Spring Boot 的 banner 和任何一行日志都会破坏帧，必须全部改道 stderr |
| 抢号链路仍是 Redis Stream，未接 RocketMQ | 缺海量堆积能力 | **刻意不换**：`XADD` 写在 `sell_one.lua` 里、和扣租约是同一个原子操作，拆开就是双写问题（扣了库存消息没发 = 少卖）。RocketMQ 用在它该在的三条链路上，见第八节 |
| `deviceId` 仍是客户端自报的参数 | 设备维度风控（一机多号判据）可被随机 deviceId 绕过——对 `holderId` 成立的那套批评，原样适用于它 | 修法是把设备指纹绑进服务端签发的令牌，但那要动 identify 协议和所有压测端；在此之前，设备判据只对诚实客户端有效，这一行就是这个边界的声明 |
| identify 的按 IP 限流是进程内存态 | 多实例下每 IP 的有效上限被放大 N 倍 | 要认真依赖它就把计数移到 Redis；演示环境先知道这回事 |
| 限流做在应用进程内 | 无效流量已经进到应用了 | 生产应前移到网关/CDN 边缘 |
| `LOCAL` 判重依赖网关粘性路由 | 无粘性路由时会白占库存名额再回滚 | 已提供 `REDIS` 模式作为替代 |
| 单机 Docker 部署 | 网络 RTT 被严重低估，本地号段的真实收益比测出来的更大 | 多机部署验证 |
| 支付是模拟的（直接改状态） | 没有异步回调、对账、幂等重试 | 刻意不做：要验证的是**超时释放**，支付集成是另一个题目 |
| `/clinic/identify` 不校验凭据，谁来要都签发身份 | 演示环境里可以换成任意患者 | 刻意的：这次修的是「身份由谁签发」的结构问题，不是把登录做完。真实系统这里换成密码 / 短信验证码即可，下游代码一行不用改 |
| 患者令牌没有有效期，也不绑设备 | 令牌泄露就等于身份泄露 | 做一半的会话管理比没有更容易让人误以为它安全，所以要么不做要么做全；见「安全边界」 |
| L1 Agent 的三路对照实验（纯规则 / 规则+Agent / 仅 Agent）还没跑 | Agent 的实际收益缺少量化对照 | L1 已接真实 LLM 并有实测决策数据（见第五节），但「规则+Agent vs 纯规则」的同条件对照还没跑 |
| 集成测试只有 Lua 并发不变量 4 个（Testcontainers） | Stream 消费、护栏执行层、放号链路仍无集成测试 | 判据层已抽成纯函数单测（GuardDecider 42 个），缺的是「纯函数之外的接线」那一层 |
| 没有组件测试 | 「点按钮后变 disabled」这类渲染行为靠手点 | 需要 jsdom + `@vue/test-utils`；逻辑复杂度不在渲染上，优先级低 |

---

## 十一、这个项目参考了什么

主动交代借鉴关系，比装原创可信得多：

- **三层库存的号段思路** ← 美团 Leaf 的发号器。**我的改动**：把号段从「发 ID」挪到「扣库存」，因此必须处理原方案没有的**回收问题** —— ID 用不完无所谓，库存用不完就是少卖。租约机制就是这么来的。
- **AIMD** ← TCP 拥塞控制，以及 Sentinel 的自适应限流思路。**为什么自己写**：这个项目的核心命题就是控制策略本身，用现成组件等于把要研究的东西黑盒掉了。
- **Agent 的上下文摘要与工具调用** ← 读过的开源 Java AI 框架实现。
