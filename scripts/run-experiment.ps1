<#
.SYNOPSIS
    跑一轮完整的压测实验：重置 → 压测 → 等消费追平 → 一致性校验 → 打印报告。

.DESCRIPTION
    这个脚本存在的唯一理由是「让实验可重复」。手工点接口迟早会漏掉某一步，
    而漏掉重置的那一轮数据是废的，你还不一定看得出来。

.EXAMPLE
    .\run-experiment.ps1
    .\run-experiment.ps1 -Concurrency 400 -Duration 30 -Shape burst
    .\run-experiment.ps1 -Shape skew -Buckets 4
#>
param(
    [int]    $Concurrency = 200,
    [int]    $Duration    = 20,
    [int]    $Stock       = 1000,
    [int]    $Buckets     = 8,
    # constant | burst | skew  （注意不能叫 -Profile，那是 PowerShell 的保留变量）
    [string] $Shape       = "constant",
    # 控制面 / 预热 / 校验走这个地址。P5 杀实例时它必须指向「存活」的那个。
    [string] $BaseUrl     = "http://127.0.0.1:8090",
    # 压测流量打这个地址；留空则与 BaseUrl 相同。
    # P5 的关键：流量必须打到将被杀掉的实例，否则它手里没有本地号段，
    # 杀掉也不会产生少卖，实验就什么都验证不了。
    [string] $LoadUrl     = "",
    [long]   $PoolId      = 1001,
    [string] $Note        = "",

    # 压测进行到一半时注入的故障，对应实验方案的 P4/P5/P6。
    # none           不注入
    # mysql-pause    冻结 MySQL → 消费端卡住，观察积压与控制面是否主动降速（P4）
    # redis-pause    冻结 Redis → 观察数据面能否优雅降级（P4 变体）
    # kill-app       强杀 -ChaosPort 上的实例 → 观察少卖与租约回收（P5）
    # redis-failover 主从切换 → 观察异步复制丢写对库存的影响（P6）
    [ValidateSet("none", "mysql-pause", "redis-pause", "kill-app", "redis-failover")]
    [string] $Chaos       = "none",
    # 故障持续秒数（pause 类）
    [int]    $ChaosSeconds = 8,
    # 最早注入时刻（压测第几秒）。注入仍然要等「有货且在卖」，这个值只是下限。
    # P5 需要把它调大（建议 15）：kill-app 要验证的是「被杀实例手里的号段丢不丢」，
    # 太早注入时那个实例还没领到多少号段，等于没测到东西。
    [int]    $ChaosAfter   = 3,
    # kill-app 要杀哪个端口上的实例
    [int]    $ChaosPort   = 8091,

    # 压测期间的风控阈值。
    #
    # **默认调高到 500 是必需的，不是偷懒。** 压测流量的形态是「20 万患者各请求两三次」，
    # 这在患者频次判据看来就是全员高频：实测阈值 3 时 55 万个请求里 52 万被降权丢进
    # 20/s 的慢车道，成交从 7.6 万掉到 2.4 万，而报告只显示"被限流拒绝"——
    # 你以为在测引擎的吞吐，实际测的是慢车道的速率。
    #
    # 要测风控本身，用 A/B 对照（正常患者各自设备 vs 一机多号批量代抢），
    # 那才是风控的目标场景；高压画像测不出风控的好坏，只会把引擎的数字弄脏。
    [int]    $RiskThreshold = 500
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LoadUrl)) { $LoadUrl = $BaseUrl }

function Write-Section($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

# ---------- 0. 服务在不在 ----------
Write-Section "检查服务"
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
    Write-Host "服务状态: $($health.status)" -ForegroundColor Green
} catch {
    Write-Host "连不上 $BaseUrl，先启动应用：" -ForegroundColor Red
    Write-Host "  cd $projectRoot; mvn spring-boot:run" -ForegroundColor Yellow
    exit 1
}

# ---------- 1. 重置 ----------
Write-Section "重置实验环境"
$preheat = Invoke-RestMethod -Method Post -TimeoutSec 30 `
    -Uri "$BaseUrl/verify/preheat?poolId=$PoolId&totalStock=$Stock&buckets=$Buckets"
Write-Host ("轮次 #{0}  商品 {1}  库存 {2}  活跃桶 {3}" -f `
    $preheat.round, $preheat.poolId, $preheat.totalStock, $preheat.activeBuckets)
if ($preheat.agentEnabled) {
    Write-Host "L1 Agent: 已启用" -ForegroundColor Green
} else {
    Write-Host "L1 Agent: 未启用（只跑 L0 规则控制）" -ForegroundColor DarkGray
}

# 显式把风控阈值摆到一边，并且「把它打出来」。
# 关键不是"调高"，而是"每轮报告都写明本轮的风控阈值"——
# 之前风控静默吃掉 95% 的流量而报告里没有任何痕迹，就是因为没人声明过这个前提。
$rt = Invoke-RestMethod -Method Post -TimeoutSec 15 `
    -Uri "$BaseUrl/control/config?param=riskcontrol.threshold&value=$RiskThreshold&reason=压测：避免高压画像被风控当成黄牛"
Write-Host ("风控阈值: {0}  (本轮的性能数字以此为前提)" -f $rt.appliedValue) -ForegroundColor DarkGray

# ---------- 2. 压测 ----------
Write-Section "开始压测"
$classes = Join-Path $projectRoot "target\classes"
if (-not (Test-Path $classes)) {
    Write-Host "target\classes 不存在，先编译：mvn compile" -ForegroundColor Red
    exit 1
}
# 压测期间按 500ms 采样一次控制面指标。
#
# 没有这个采样，故障注入类实验（P4/P5/P6）等于白做：报告只看结束状态，
# 而那时积压已经消化、限流阈值已经回升、一切看起来和 P1 没区别 ——
# 故障究竟有没有产生影响、控制面到底反应了没有，全都看不见。要看的是瞬态。
# 采样之前先确认 /control/metrics 已经反映出 preheat 的结果。
#
# 为什么需要这一步：metrics 里的 bucketSum 是定时任务采集的（1 秒一轮），
# 不是实时读 Redis。preheat 返回后立刻采样，第一行拿到的是**上一轮的余量** ——
# 实测 preheat 到 10000 之后立即读，metrics 还报着上一轮的 765842。
# 那一行会被「有货时长」统计当成本轮有货，把结论往好的方向拉。
#
# 不用「丢弃第一行」来掩盖：那样就看不出 metrics 到底刷新了没有。
# 这里显式等到数字对上，等不到就说出来 —— 之后所有基于 metrics 的判断
# （包括故障注入的有货探测）都建立在这个前提上。
$metricsFresh = $false
for ($i = 0; $i -lt 20; $i++) {
    try {
        $mm = Invoke-RestMethod -Uri "$BaseUrl/control/metrics" -TimeoutSec 3
        if ([int]$mm.bucketSum -eq $Stock) { $metricsFresh = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 250
}
if ($metricsFresh) {
    Write-Host ("  指标已刷新（bucketSum={0} 与本轮库存一致），开始采样" -f $Stock) -ForegroundColor DarkGray
} else {
    Write-Host "  警告：等了 5 秒，/control/metrics 的 bucketSum 仍和本轮库存不一致。" -ForegroundColor Yellow
    Write-Host "        采样曲线的开头几行可能是上一轮的残留，「有货时长」会偏乐观。" -ForegroundColor Yellow
}

$sampleFile = Join-Path $env:TEMP ("fp-samples-" + (Get-Random) + ".csv")
$sampler = Start-Job -ScriptBlock {
    param($url, $file, $seconds)
    "ts,pending,limitQps,p99Window,effQps,rejectRate,bucketSum,leaseHeld" | Out-File $file -Encoding utf8
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $m = Invoke-RestMethod -Uri "$url/control/metrics" -TimeoutSec 3
            ("{0},{1},{2},{3},{4},{5},{6},{7}" -f (Get-Date -Format "HH:mm:ss.fff"),
                $m.streamPending, $m.limitQps, $m.p99Ms, [math]::Round($m.effectiveQps,0),
                [math]::Round($m.rejectRate,3), $m.bucketSum, $m.leaseHeld) |
                Out-File $file -Append -Encoding utf8
        } catch { }
        Start-Sleep -Milliseconds 500
    }
} -ArgumentList $BaseUrl, $sampleFile, ($Duration + 25)

if ($LoadUrl -ne $BaseUrl) {
    Write-Host ("压测目标 {0}   控制面/校验走 {1}" -f $LoadUrl, $BaseUrl) -ForegroundColor Yellow
}

$runStartedAt = Get-Date

if ($Chaos -eq "none") {
    & java -cp $classes com.flashpilot.tools.LoadGenerator `
        --url $LoadUrl --item $PoolId --concurrency $Concurrency `
        --duration $Duration --profile $Shape
} else {
    # 有故障注入时，压测放到后台跑，主线程负责在中途按下故障开关。
    # 注入时机取压测时长的一半 —— 太早的话系统还没进入稳态，太晚就来不及观察恢复过程。
    # 触发时机是探测驱动的，不是定时的。
    #
    # 原来写死「压测过半时触发」，结果 P4 实测：库存 60000 在第 6.5 秒就被抽干，
    # 而故障在第 20 秒才注入 —— 冻结 MySQL 打在了一个已经无号可卖的系统上。
    # 报告照样给出「40 秒、39194 req/s、五条等式全过」，每个数字都真实，
    # 但它们描述的不是「故障期间还能不能正常下单」这个待验证的问题。
    # 那一轮真正证明的只是「消费者卡死时 Stream 不丢数据」（积压冻在 12675 条，
    # 恢复后全部落库、零死信）—— 有价值，但不是 P4 声称要测的东西。
    #
    # 所以改成：等到系统「已经开始卖」且「手里仍有号」的那一刻立即注入。
    # 下限 3 秒让 JIT 和控制面进稳态，上限 Duration/2 保证故障之后还有观察窗口。
    $chaosFloorSec = [math]::Max(1, $ChaosAfter)
    $chaosCeilSec  = [math]::Max($chaosFloorSec + 1, [int]($Duration / 2))
    Write-Host ("故障注入：{0}  等系统进入「有货且在卖」状态后触发（第 {1}~{2} 秒之间）" -f $Chaos, $chaosFloorSec, $chaosCeilSec) -ForegroundColor Yellow
    $job = Start-Job -ScriptBlock {
        param($classes, $url, $PoolId, $Concurrency, $Duration, $Shape)
        & java -cp $classes com.flashpilot.tools.LoadGenerator `
            --url $url --item $PoolId --concurrency $Concurrency `
            --duration $Duration --profile $Shape 2>&1
    } -ArgumentList $classes, $LoadUrl, $PoolId, $Concurrency, $Duration, $Shape

    Start-Sleep -Seconds $chaosFloorSec
    $probeDeadline = (Get-Date).AddSeconds($chaosCeilSec - $chaosFloorSec)
    $chaosStock    = -1        # 注入那一刻的可售号源（桶余量 + 实例持有）
    $soldSomething = $false    # 是否观测到「卖过货」
    while ((Get-Date) -lt $probeDeadline) {
        try {
            $pm = Invoke-RestMethod -Uri "$BaseUrl/control/metrics" -TimeoutSec 2
            $avail = [int]$pm.bucketSum + [int]$pm.leaseHeld
            if ([int]$pm.effectiveQps -gt 0 -or $avail -lt $Stock) { $soldSomething = $true }
            if ($soldSomething -and $avail -gt 0) { $chaosStock = $avail; break }
            if ($soldSomething -and $avail -le 0) { $chaosStock = 0; break }   # 已售罄，不必再等
        } catch { }
        Start-Sleep -Milliseconds 300
    }
    if ($chaosStock -lt 0) {
        # 探测窗口内没能确认状态（控制面探测失败或压测还没起来），按上限时刻注入。
        try { $pm = Invoke-RestMethod -Uri "$BaseUrl/control/metrics" -TimeoutSec 2; $chaosStock = [int]$pm.bucketSum + [int]$pm.leaseHeld } catch { $chaosStock = -1 }
    }
    $chaosAt = Get-Date
    $chaosElapsed = ($chaosAt - $runStartedAt).TotalSeconds
    Write-Host ("[{0}] 触发 {1}   注入时刻可售号源 {2}（压测第 {3:N1} 秒）" -f $chaosAt.ToString("HH:mm:ss"), $Chaos, $chaosStock, $chaosElapsed) -ForegroundColor Red
    if ($chaosStock -eq 0) {
        Write-Host "    !!! 注入时号源已售罄 —— 本轮故障打在空载系统上，只能验证「已发出的单不丢」，" -ForegroundColor Red
        Write-Host "    !!! 不能用来支持「故障期间仍可正常下单」。把 -Stock 加大或 -Concurrency 降低后重跑。" -ForegroundColor Red
    }
    & (Join-Path $PSScriptRoot "chaos.ps1") -Action $Chaos -Seconds $ChaosSeconds -Port $ChaosPort

    Write-Host "等压测结束…" -ForegroundColor DarkGray
    Wait-Job $job -Timeout ($Duration * 3) | Out-Null
    $loadOutput = Receive-Job $job
    $loadOutput | ForEach-Object { Write-Host $_ }
    Remove-Job $job -Force -ErrorAction SilentlyContinue

    # ---------- 用压测器的观测来判定故障是否真的落地 ----------
    #
    # 为什么不能信杀进程那一侧的校验：满载（3 万+ req/s）时
    # Get-Process / Get-NetTCPConnection 的枚举会瞬时失败，健康检查的 2 秒超时也会正常超时，
    # 于是「进程没了」「端口不听了」这两种判据都会给出假阳性 ——
    # 实测两次都报告「已确认退出」，而目标进程其实又活了 15~21 秒。
    #
    # 压测器是唯一真正在和服务器对话的一方：它如果一条异常都没有，
    # 就说明服务端在整个压测窗口内始终可用，故障根本没落在窗口里，本轮数据不能采信。
    if ($Chaos -eq "kill-app") {
        $errLine = $loadOutput | Select-String -Pattern "异常\s+(\d+)" | Select-Object -First 1
        $errCount = if ($errLine) { [int]($errLine.Matches[0].Groups[1].Value) } else { -1 }
        Write-Host ""
        if ($errCount -gt 0) {
            Write-Host ("故障落地确认：压测器观测到 {0} 次请求失败，实例确实在压测窗口内下线了" -f $errCount) -ForegroundColor Green
        } elseif ($errCount -eq 0) {
            Write-Host "!!! 压测器异常数为 0 —— 实例在整个压测窗口内始终可用 !!!" -ForegroundColor Red
            Write-Host "!!! kill 没有在窗口内生效，本轮 P5 数据无效，不要写进实验表格 !!!" -ForegroundColor Red
            Write-Host "    原因：JVM 收到 kill 后可能还要十几秒才真正停止服务，而压测已经结束。" -ForegroundColor Yellow
            Write-Host "    应对：把 -Duration 加长（建议 >= 90），让故障之后仍有足够观测窗口。" -ForegroundColor Yellow
        } else {
            Write-Host "没能从压测输出里解析出异常数，无法判定故障是否落地" -ForegroundColor Yellow
        }
    }
}

# ---------- 2.5 采样结果：故障到底有没有产生影响 ----------
Write-Section "压测期间的瞬态（500ms 采样）"
Stop-Job $sampler -ErrorAction SilentlyContinue
Receive-Job $sampler -ErrorAction SilentlyContinue | Out-Null
Remove-Job $sampler -Force -ErrorAction SilentlyContinue

if (Test-Path $sampleFile) {
    $rows = Import-Csv $sampleFile
    if ($rows.Count -gt 0) {
        $peakPending = ($rows | Measure-Object -Property pending -Maximum).Maximum
        $minLimit    = ($rows | Measure-Object -Property limitQps -Maximum -Minimum).Minimum
        $maxLimit    = ($rows | Measure-Object -Property limitQps -Maximum).Maximum
        $maxP99      = ($rows | Measure-Object -Property p99Window -Maximum).Maximum
        $maxReject   = ($rows | Measure-Object -Property rejectRate -Maximum).Maximum
        $peakRow     = $rows | Sort-Object { [int]$_.pending } -Descending | Select-Object -First 1

        Write-Host ("  采样点数        {0}" -f $rows.Count)
        Write-Host ("  峰值积压        {0} 条  (发生在 {1})" -f $peakPending, $peakRow.ts) -ForegroundColor $(if([int]$peakPending -gt 5000){"Yellow"}else{"Gray"})
        Write-Host ("  限流阈值区间    {0} → {1}" -f $maxLimit, $minLimit)
        Write-Host ("  窗口 P99 峰值   {0} ms" -f $maxP99)
        Write-Host ("  误拒率峰值      {0}" -f $maxReject)
        # 「有货时长」是这份报告里最容易被忽略、却最能决定结论有效性的一个数。
        #
        # 采样里 bucketSum + leaseHeld 都为 0 的那些点，系统在打空气：
        # 每个请求都稳稳地返回「号源已满」，吞吐很高、延迟很低、等式全过 ——
        # 看起来是一轮漂亮的压测，实际上什么都没测。P4 有 84% 的时间是这样。
        # 不打印这个数，你会以为测了 40 秒，其实只测了 6.5 秒。
        $inStock = @($rows | Where-Object { ([int]$_.bucketSum + [int]$_.leaseHeld) -gt 0 })
        $inStockPct = if ($rows.Count -gt 0) { 100.0 * $inStock.Count / $rows.Count } else { 0 }
        $lastInStock = if ($inStock.Count -gt 0) { $inStock[-1].ts } else { "（全程无货）" }
        $stockColor = if ($inStockPct -ge 70) { "Green" } elseif ($inStockPct -ge 30) { "Yellow" } else { "Red" }
        Write-Host ("  有货时长        {0:N1} 秒 / {1:N1} 秒  ({2:N0}% 的采样点仍有号可卖，最后有货 {3})" -f `
            ($inStock.Count * 0.5), ($rows.Count * 0.5), $inStockPct, $lastInStock) -ForegroundColor $stockColor
        if ($inStockPct -lt 30) {
            Write-Host "    ↑ 大部分时间在打空气：号早卖光了，之后的请求全是稳定返回「号源已满」。" -ForegroundColor Red
            Write-Host "      这一段的吞吐和延迟不代表抢号路径的性能。加大 -Stock 或缩短 -Duration。" -ForegroundColor Yellow
        }
        Write-Host ("  完整曲线        {0}" -f $sampleFile) -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  积压最高的 6 个采样点：" -ForegroundColor White
        $rows | Sort-Object { [int]$_.pending } -Descending | Select-Object -First 6 |
            ForEach-Object { Write-Host ("    {0}  积压={1,-7} 限流={2,-6} P99={3,-6} 误拒={4}" -f $_.ts, $_.pending, $_.limitQps, $_.p99Window, $_.rejectRate) }
    } else {
        Write-Host "  没有采到样本（应用可能在压测中不可用）" -ForegroundColor Yellow
    }
} else {
    Write-Host "  采样文件不存在" -ForegroundColor Yellow
}

# ---------- 3. 等消费者追平 ----------
Write-Section "等待消费者把 Stream 追平"
# 超时按「实测消费速率 + 当前积压」自适应，而不是写死 20 秒。
# 写死的坏处：库存一大就必然超时，一致性等式全部误判，实验结果没法用。
# 停止条件有两个：排空，或者速率降到 0（真卡住了）——后者才是需要报警的情况。
$drained  = $false
$stalled  = $false
$deadline = (Get-Date).AddMinutes(5)
$prev     = [int]::MaxValue
$noProgress = 0
$tick     = 0

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $c = Invoke-RestMethod -Uri "$BaseUrl/verify/check" -TimeoutSec 20
    $now = [int]$c.unprocessed
    if ($now -le 0) { $drained = $true; break }

    if ($now -ge $prev) { $noProgress++ } else { $noProgress = 0 }
    if ($noProgress -ge 20) { $stalled = $true; break }   # 连续 10 秒没有任何推进
    $prev = $now

    $tick++
    if ($tick % 6 -eq 0) {   # 每 3 秒打一行，别刷屏
        Write-Host ("  还有 {0} 条未处理…" -f $now) -ForegroundColor DarkGray
    }
}

if ($drained) {
    Write-Host "Stream 已排空" -ForegroundColor Green
} elseif ($stalled) {
    Write-Host "积压停止下降 —— 消费者卡住了，这是一个真问题（查死信 / 连接池 / 行锁）" -ForegroundColor Red
} else {
    Write-Host "5 分钟仍未排空 —— 消费能力不足，等式②⑤ 会因此暂不判定" -ForegroundColor Yellow
}

# ---------- 4. 一致性校验 ----------
Write-Section "一致性校验"
$report = Invoke-RestMethod -Uri "$BaseUrl/verify/check" -TimeoutSec 20
foreach ($eq in $report.equations) {
    if ($eq -match "✔") {
        Write-Host "  $eq" -ForegroundColor Green
    } else {
        Write-Host "  $eq" -ForegroundColor Red
    }
}

# ---------- 5. 库存与控制面状态 ----------
Write-Section "库存与控制面"
$state   = Invoke-RestMethod -Uri "$BaseUrl/seckill/state/$PoolId" -TimeoutSec 10
$metrics = Invoke-RestMethod -Uri "$BaseUrl/control/metrics"       -TimeoutSec 10
$config  = Invoke-RestMethod -Uri "$BaseUrl/control/config"        -TimeoutSec 10

$instanceScoped = if ($LoadUrl -ne $BaseUrl) { "   ← 实例级，本轮压测打的是别的实例，此数不可用" } else { "" }
Write-Host ("  号段命中率     {0}{1}" -f $state.segmentHitRatio, $instanceScoped) -ForegroundColor $(if($instanceScoped){"DarkGray"}else{"Gray"})
Write-Host ("  借调次数       {0}" -f $state.steals)
Write-Host ("  领号段次数     {0}{1}" -f $state.refills, $instanceScoped) -ForegroundColor $(if($instanceScoped){"DarkGray"}else{"Gray"})
Write-Host ("  桶倾斜度       {0}" -f $state.bucketSkew)
Write-Host ("  尾部模式       {0}" -f $state.tailMode)
Write-Host ("  状态异常次数   {0}" -f $state.anomalies)
Write-Host ("  当前限流阈值   {0}  (配置版本 {1})" -f $config.values.'limit.qps', $config.version)
Write-Host ""
# 延迟分布、号段命中率、领号段次数这些都是 **实例级** 指标：它们只统计
# $BaseUrl 这一个进程自己处理过的请求。而 -LoadUrl 允许把流量打到别的实例
# （P5 就是这么干的：压 8091、控制面读 8090）。
#
# 那种情况下 $BaseUrl 一个抢号请求都没接过，于是这里会打印
# 「P50/P95/P99 = 0.0/0.0/0.0ms、命中率 1.0000、领号段 0 次、共 0 个样本」——
# 全是假数据，而且紧接着就被写进「记进你的实验表格」那一行。
# P99=0.0ms 抄进报告就是编数字，所以这里必须先判断指标来源是否和压测目标一致。
$sameInstance = ($LoadUrl -eq $BaseUrl)
if (-not $sameInstance) {
    Write-Host "  服务端延迟       不可用" -ForegroundColor Red
    Write-Host ("    压测打的是 {0}，而指标读的是 {1} —— 后者没处理过本轮任何抢号请求。" -f $LoadUrl, $BaseUrl) -ForegroundColor Yellow
    Write-Host ("    实例级指标（延迟分布 / 号段命中率 / 领号段次数）本轮全部无意义，" ) -ForegroundColor Yellow
    Write-Host ("    请改用上面压测器报告的客户端延迟。跨实例指标聚合还没做。") -ForegroundColor Yellow
    Write-Host ("    参考：$BaseUrl 自己的样本数 = {0}" -f $metrics.runSamples) -ForegroundColor DarkGray
} else {
    Write-Host "  服务端延迟（全程，自本轮 reset 起累计，共 $($metrics.runSamples) 个样本）" -ForegroundColor White
    Write-Host ("    P50          {0} ms" -f [math]::Round($metrics.runP50Ms, 2))
    Write-Host ("    P95          {0} ms" -f [math]::Round($metrics.runP95Ms, 2))
    Write-Host ("    P99          {0} ms" -f [math]::Round($metrics.runP99Ms, 2))
    Write-Host ("    最大          {0} ms" -f [math]::Round($metrics.runMaxMs, 2))
}
Write-Host ("  控制面窗口 P99  {0} ms  (10 秒滚动窗口，压测停止后会归零，这是设计如此)" -f `
    [math]::Round($metrics.p99Ms, 2)) -ForegroundColor DarkGray

# ---------- 5.5 风控是否干扰了本轮 ----------
#
# 这一段是必须的，不是「顺便看一下」。
# 之前风控静默吃掉 95% 的流量、把成交从 7.6 万压到 2.4 万，而报告里没有任何痕迹——
# 因为风控丢弃和限流拒绝共用一个计数器，也因为没人在报告里声明过风控的状态。
# **一个只在被主动查询时才说话的指标，出事的时候一定是沉默的。**
Write-Section "风控对本轮的影响"
$dash = Invoke-RestMethod -Uri "$BaseUrl/admin/dashboard" -TimeoutSec 10
$rk = $dash.risk
$reqTotal = [math]::Max(1, $metrics.runSamples)
$dropPct  = $rk.slowLaneDropped * 100.0 / $reqTotal
Write-Host ("  降权判定       {0} 次" -f $rk.demoted)
Write-Host ("  慢车道         通过 {0} / 丢弃 {1}  (占本轮请求 {2}%)" -f `
    $rk.slowLanePassed, $rk.slowLaneDropped, [math]::Round($dropPct, 1))
# 打印生效阈值而不只是配置值：高负载下阈值会被噪声底自动抬高
# （见 RiskControlService.effectiveThreshold），只看配置值会误以为自己调的参数在起作用。
# 注意这里是压测「结束后」采样，流量停了、窗口轮转过，噪声底通常已回到 0；
# 运行中的真实值要看风控事件里的「超阈值 N」——那个 N 才是当时实际用的门槛。
Write-Host ("  CMS 噪声底     {0}   (配置阈值 {1} → 生效 {2}，配置值仍生效={3})" -f `
    $rk.cmsNoiseFloor, $config.values.'riskcontrol.threshold', `
    $rk.effectiveThreshold, $rk.configuredThresholdInEffect)
Write-Host ("  CMS 内存       {0} KB (固定，不随 key 数量增长)" -f $rk.cmsMemoryKb)

$contaminated = $dropPct -ge 1.0
if ($contaminated) {
    Write-Host ""
    Write-Host ("  !! 本轮 {0}% 的请求被风控丢弃，性能数字不代表引擎能力。" -f [math]::Round($dropPct,1)) -ForegroundColor Red
    Write-Host "     用 -RiskThreshold 500 重跑。" -ForegroundColor Red
    Write-Host "     注意这未必是缺陷：压测流量是「20 万患者分 55 万请求」，泊松分布的尾部里" -ForegroundColor DarkGray
    Write-Host "     确实有患者在窗口内请求 7+ 次，风控把他们判成高频是正确行为。" -ForegroundColor DarkGray
    Write-Host "     判断方法：看 /admin/risk/events 的理由。写「频次估计 N 超阈值 M」且 N 略大于 M" -ForegroundColor DarkGray
    Write-Host "     是真实尾部；N 远大于 M 且几乎所有患者都命中，才是噪声底盖过阈值那个缺陷。" -ForegroundColor DarkGray
} elseif ($null -eq $rk.configuredThresholdInEffect) {
    # 字段缺失必须「显式报错」，不能让它沉默地走进某个分支。
    #
    # 这里刚踩过：字段从 criteriaHealthy 改名成 configuredThresholdInEffect 之后，
    # 旧写法 `-not $rk.criteriaHealthy` 读到 $null，而 PowerShell 里 -not $null 恒为 $true，
    # 于是噪声底 2、阈值 500 的健康状态被报成「判据正在退化」——一个纯粹的假警报。
    #
    # 这是本项目第三次栽在同一个模式上（前两次：前端读 r.guardNote 而后端叫 note；
    # 放号脚本读 $s.id 而接口返回 scheduleId）。共同点是
    # **读一个不存在的字段去做判断，语言默默给了个假值，条件于是恒定**。
    # 所以凡是拿字段值做分支的地方，都要先断言字段存在。
    Write-Host ""
    Write-Host "  !! 看板缺少 configuredThresholdInEffect 字段，无法判断判据健康度。" -ForegroundColor Red
    Write-Host "     接口字段可能改过名，去核对 AdminController 的 risk 分组。" -ForegroundColor Red
} elseif (-not $rk.configuredThresholdInEffect) {
    Write-Host ""
    Write-Host ("  !! 噪声底 {0} 已接管阈值：运营配置的 {1} 不再生效，实际按 {2} 判定。" -f `
        $rk.cmsNoiseFloor, $config.values.'riskcontrol.threshold', $rk.effectiveThreshold) -ForegroundColor Yellow
    Write-Host "     判据本身仍然可用（自适应抬升保证了这一点），但运营调阈值已经不起作用。" -ForegroundColor Yellow
    Write-Host "     想让配置值重新生效：加大 CMS_WIDTH 或缩短风控窗口。" -ForegroundColor Yellow
} else {
    Write-Host "  风控未干扰本轮（丢弃 < 1%，判据健康）" -ForegroundColor Green
}

# ---------- 6. 结论 ----------
Write-Section "结论"
if ($report.passed) {
    Write-Host "全部等式通过：零超卖、零少卖、链路守恒。" -ForegroundColor Green
} else {
    Write-Host "校验未通过 —— 这是好事，说明你的校验器真的能发现问题。" -ForegroundColor Yellow
    Write-Host ("  超卖 {0}  少卖 {1}  守恒残差 {2}  未处理 {3}" -f `
        $report.oversold, $report.undersold, $report.vanished, $report.unprocessed)
    if ($report.vanished -gt 0) {
        Write-Host "  守恒残差 > 0 说明库存凭空消失了，优先查租约回收。" -ForegroundColor Yellow
    }
}

$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host ""
Write-Host "把这一行记进你的实验表格：" -ForegroundColor Cyan
# 风控阈值必须进这一行：没有它，这个数字在半年后无法解释。
# 实验数据的可比性靠「把前提写进结果」保证，不靠记忆。
if ($LoadUrl -ne $BaseUrl) {
    # 跨实例压测时实例级数字全是 0，绝对不能进表格 —— 宁可不给这一行。
    Write-Host ("  {0} | 并发={1} 画像={2} 桶={3} 库存={4} 风控阈值={5} | 服务端延迟=不可用(跨实例，用客户端数字) | 命中率=不可用 | 借调={6} | 风控丢弃={7}% | 通过={8} | {9}" -f `
        $stamp, $Concurrency, $Shape, $Buckets, $Stock, $config.values.'riskcontrol.threshold', `
        $state.steals, [math]::Round($dropPct, 1), $report.passed, $Note) -ForegroundColor Yellow
    Write-Host "  ↑ 延迟一栏请从上面压测器的「延迟（客户端观测，含排队）」抄，别抄服务端那几个 0。" -ForegroundColor Yellow
} else {
    Write-Host ("  {0} | 并发={1} 画像={2} 桶={3} 库存={4} 风控阈值={5} | 服务端 P50/P95/P99={6}/{7}/{8}ms | 命中率={9} | 借调={10} | 风控丢弃={11}% | 通过={12} | {13}" -f `
        $stamp, $Concurrency, $Shape, $Buckets, $Stock, $config.values.'riskcontrol.threshold', `
        [math]::Round($metrics.runP50Ms, 2), [math]::Round($metrics.runP95Ms, 2), [math]::Round($metrics.runP99Ms, 2), `
        $state.segmentHitRatio, $state.steals, [math]::Round($dropPct, 1), $report.passed, $Note)
}
