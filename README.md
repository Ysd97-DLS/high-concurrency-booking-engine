# FlashPilot

**具备 AI 自治控制面的高并发秒杀系统。**

核心命题只有一句：**把 LLM 放在秒级的控制面，而不是毫秒级的请求路径上。**

- **数据面**（`dataplane`）：请求路径。三层库存 + Lua 原子扣减 + 本地号段，绝大多数请求只做一次内存 CAS，**没有任何 LLM 调用，也没有任何分布式锁**。
- **控制面**（`controlplane`）：反馈回路。L0 规则控制器（AIMD）秒级止损兜底，L1 LLM Agent 做跨指标归因与参数提案，配套护栏五件套。
- **验证层**（`verify`）：把「我保证不超卖」变成四组可执行的断言，并在故障注入下反复跑。

> LLM 一次调用是秒级，秒杀请求预算是毫秒级，差三个数量级 —— 所以它只能待在本来就是秒级的反馈回路里。这是整个项目最值得聊的架构取舍。

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
docker compose up -d              # redis + mysql
docker compose ps                 # 等两个都 healthy
```

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

想要 Prometheus + Grafana（W3 用）：

```powershell
docker compose --profile obs up -d
# Grafana http://localhost:3000  (匿名可进)
# Prometheus http://localhost:9090
```

### 2. 起应用

```powershell
mvn spring-boot:run
```

看到 `热配置就绪` 和 `Stream 消费者启动` 就算好了。启动日志里还会有一行
`调度池容量检查通过：15 个定时任务 / 18 个线程（余 3）`——
那是一条会自己喊的不变量，红字就说明池子配小了（详见第五节）。

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
mvn test              # 后端 170 个
cd frontend; npm test # 前端 57 个
```

227 个测试，**刻意只覆盖两类东西**：纯逻辑单元，以及「坏了不会报错」的不变量。

不追求覆盖率，因为这个项目 73 个缺陷里有 26 个属于「系统看起来正常但已停止工作」——
那类问题不是靠多测几个分支发现的，而是靠把**不变量**写成断言。举三个例子：

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
├── docker-compose.yaml           redis + mysql (+obs profile: prometheus/grafana)
├── docker-compose.ha.yaml        主从+哨兵，P6 故障注入实验用
├── sql/
│   ├── 01-schema.sql             引擎表（号池、事件、审计）
│   ├── 02-clinic-schema.sql      挂号域 6 张表 + 科室医生种子
│   ├── 03-release-progress.sql   released_slots 列（放号进度）
│   ├── 04-demo-data.sql          未来 7 天的演示排班，可反复执行
│   └── 05-reconcile-log.sql      对账补偿留档（改动号源账目的动作必须可审计）
├── deploy/                       prometheus 与 grafana 配置
├── scripts/
│   ├── seed-demo.ps1             灌演示数据 + 走真实放号链路（第一次跑先用这个）
│   ├── run-experiment.ps1        一键跑完整实验（压测最常用）
│   ├── chaos.ps1                 故障注入
│   ├── start-ha.ps1              起主从+哨兵
│   └── wrk-seckill.lua           wrk 脚本（以后在 WSL/Linux 用）
├── frontend/                     Vue 3 + Vite 前端，构建产物打进 jar
│   └── src/{api,composables,constants,views}
└── src/main/
    ├── resources/
    │   ├── lua/                  10 个 Lua 脚本，数据面的核心都在这
    │   └── static/app/           前端构建产物（npm run build 生成，不提交）
    └── java/com/flashpilot/
        ├── config/               配置、Lua 加载、实例身份
        ├── dataplane/            **引擎**：限流 → 判重 → 库存 → 发事件
        │   ├── stock/            三层库存、桶间借调、号段租约
        │   ├── limit/            进程内令牌桶
        │   ├── stream/           Stream 生产与消费、pending 抢占
        │   └── order/            事件落库（幂等 + 不超卖兜底）
        ├── clinic/               **业务域**：挂号。引擎不知道自己在卖号还是卖鞋
        │   ├── domain/           六状态机、排班、患者、读模型
        │   ├── risk/             三层风控（Count-Min Sketch）+ 慢车道
        │   ├── admin/            排班管理、分批放号
        │   ├── reconcile/        对账补偿（四道闸门，判据抽成纯函数可单测）
        │   └── api/              患者端接口
        ├── controlplane/         控制面
        │   ├── config/           热配置中心（Hash + Pub/Sub + 兜底轮询）
        │   ├── guard/            护栏五件套（判据抽成 GuardDecider，纯函数 42 个单测）
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
| POST | `/seckill/{poolId}?holderId=&deviceId=` | 抢号。售罄/限流也返回 HTTP 200 + 业务码，避免压测工具统计成 error |
| GET | `/seckill/state/{poolId}` | 库存分布、号段命中率、借调次数、桶倾斜度 |

业务码：`200` 成功 / `4001` 号已售罄 / `4002` 一人一单 / `4030` 风控拉黑 / `4290` 被限流 / `5000` 内部异常。

**所有业务错误都是 HTTP 200 + `code`**，只有传输层错误才是非 200。理由：秒杀场景里「售罄」「限流」「重复」都是高频正常路径，做成 4xx 会让压测工具把它们统计成错误率，也会让浏览器控制台刷一片红。

### 患者端（业务域）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/clinic/departments` | 科室列表 |
| GET | `/clinic/schedules?departmentId=&date=` | 某科室某天可约排班（默认查明天） |
| GET | `/clinic/appointments?patientId=&limit=` | 我的预约（带医生/科室，一次 IN 查询避免 N+1） |
| GET | `/clinic/appointments/{apptNo}` | 预约详情 |
| POST | `/clinic/appointments/{apptNo}/pay` | 模拟支付 → BOOKED |
| POST | `/clinic/appointments/{apptNo}/refund` | 退号 → REFUNDED，号源归还号池 |
| POST | `/clinic/appointments/{apptNo}/complete` | 就诊完成 → COMPLETED |
| POST | `/clinic/appointments/{apptNo}/no-show` | 标记失约 → NO_SHOW（累计 3 次限制预约 30 天） |
| GET | `/clinic/server-time` | 服务端时间，前端倒计时校准用 |

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

### 护栏五件套

1. **白名单** —— 只能改 `ConfigParam` 里列的参数
2. **区间钳制** —— 越界钳到边界（方向对就让它生效一部分）
3. **幅度限制** —— 单次不超过当前值的 `max-change-ratio`
4. **冷却期** —— 同参数两次变更的最小间隔；人工变更可绕过
5. **审计 + 一键回滚** —— 通过和驳回都落表

再加一条兜底：**Agent 不可用时自动退化为纯 L0，功能与安全性不受影响。**它是可选增强，不是关键路径依赖。

### 开启 Agent

支持任何 OpenAI 兼容端点（DeepSeek / Moonshot / 通义 / 本地 vLLM、Ollama）：

```powershell
$env:LLM_API_KEY  = "sk-你的key"
$env:LLM_BASE_URL = "https://api.deepseek.com"
$env:LLM_MODEL    = "deepseek-chat"
# application.yml 里把 flashpilot.control.agent.enabled 改成 true
mvn spring-boot:run
```

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

### 四组一致性等式

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

## 七、已知缺陷与后续计划

主动写出缺陷是成熟度信号，也是面试时的加分项。

| 缺陷 | 影响 | 计划 |
|---|---|---|
| MCP 目前是 HTTP 形态（`/mcp/tools`、`/mcp/call`） | 不能被标准 MCP 客户端直接发现 | 换成 stdio / SSE 传输（W5） |
| 消息中间件用 Redis Stream，未接 RocketMQ | 缺事务消息、延迟消息、海量堆积能力 | 投递层已抽象，换实现即可 |
| 限流做在应用进程内 | 无效流量已经进到应用了 | 生产应前移到网关/CDN 边缘 |
| Grafana 只配了数据源，没有大盘 | 需要手工建面板 | W3 的任务，自己建更能理解指标 |
| `LOCAL` 判重依赖网关粘性路由 | 无粘性路由时会白占库存名额再回滚 | 已提供 `REDIS` 模式作为替代 |
| 单机 Docker 部署 | 网络 RTT 被严重低估，本地号段的真实收益比测出来的更大 | 多机部署验证 |
| 支付是模拟的（直接改状态） | 没有异步回调、对账、幂等重试 | 刻意不做：要验证的是**超时释放**，支付集成是另一个题目 |
| 前端没有登录，患者身份靠输入框切换 | 任何人都能查任何人的预约 | 演示项目的取舍；真实系统这里要接实名认证 |
| L1 Agent 的三路对照实验（纯规则 / 规则+Agent / 仅 Agent）还没跑 | Agent 的实际收益缺少量化 | 需要接真实 LLM key 后跑一轮 |
| 测试只覆盖纯逻辑单元 | Lua 脚本、Stream 消费、护栏、放号链路都没有自动化测试，靠压测脚本端到端验证 | 这几处都需要 Redis + MySQL，得上 Testcontainers。护栏（`GuardRail`）优先级最高——它是唯一允许 Agent 改生产参数的地方，而它出过一个「参数永远调不动」的缺陷 |
| 没有组件测试 | 「点按钮后变 disabled」这类渲染行为靠手点 | 需要 jsdom + `@vue/test-utils`；逻辑复杂度不在渲染上，优先级低 |

---

## 八、这个项目参考了什么

主动交代借鉴关系，比装原创可信得多：

- **三层库存的号段思路** ← 美团 Leaf 的发号器。**我的改动**：把号段从「发 ID」挪到「扣库存」，因此必须处理原方案没有的**回收问题** —— ID 用不完无所谓，库存用不完就是少卖。租约机制就是这么来的。
- **AIMD** ← TCP 拥塞控制，以及 Sentinel 的自适应限流思路。**为什么自己写**：这个项目的核心命题就是控制策略本身，用现成组件等于把要研究的东西黑盒掉了。
- **Agent 的上下文摘要与工具调用** ← 读过的开源 Java AI 框架实现。
