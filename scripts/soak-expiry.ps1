<#
.SYNOPSIS
  超时释放的量级验证：压出一大批待支付单，等它们集体到期，盯着号源怎么回来。

.DESCRIPTION
  这个脚本补的是一个真实空白：**所有压测都只跑 20-90 秒，而支付时限默认 10 分钟**。
  于是超时释放这条路径从来没在量级上跑过 —— 它做的是「先改 MySQL 状态、再还 Redis 号源」
  两步，而这条路径出错的方式是**双重归还 = 超卖**，不是少卖。

  跑法：把应用以 `--flashpilot.clinic.pay-minutes=1` 启动，压一轮攒下几万张待支付单，
  然后什么都不做，只采样，看那几万个号怎么回到桶里。

  三件必须盯住的事：

    1. **号源回来的总量正好等于发出去的量** —— 多了就是双重归还（超卖），少了是漏释放。
       靠等式③ 的残差判断，它对两个方向都敏感。

    2. **桶余量的最大值不超过总号数** —— 这是超卖的直接体征。
       等式③ 的残差是负数时就意味着「占号比总号数多」，但那要等采样稳定；
       桶余量超过 total_slots 是更早、更硬的信号。

    3. **释放速率跟得上** —— 这一条第一次跑就抓到了缺陷：`fixedDelay` 的语义是
       「上一次**结束**后再等 3 秒」，所以有积压时周期 = 处理耗时 + 3 秒空等，
       实测 6 万张单同时到期时相邻批间隔 4.28 秒、吞吐只有 117 个/秒，
       而「单批 500 / 间隔 3 秒」给人的印象是 167 个/秒。
       连续 88 批全部触达上限 —— 日志一直在喊「仍有积压」，而系统就是不加快。
       改成「本批跑满就立刻接着跑下一批」之后：**394 个/秒，6 万张单 152 秒排空**。

  为什么不用 run-experiment.ps1 加个参数：那个脚本的采样在压测结束就停了，
  而这里要看的**全部发生在压测结束之后**。两者关心的时间窗根本不重叠。

.PARAMETER ScheduleId
  用哪个排班。默认 1001（压测号池）。

.PARAMETER Stock
  号池总量。要足够大才能攒出有意义的待支付积压。

.PARAMETER Concurrency
  压测并发。

.PARAMETER LoadSeconds
  压测时长。攒单阶段。

.PARAMETER WatchMinutes
  压测结束后观察多久。必须大于 pay-minutes，否则单子还没到期就收工了。

.PARAMETER BaseUrl
  应用地址。

.EXAMPLE
  # 先以 1 分钟支付时限启动应用：
  #   java -jar target\flashpilot-0.1.0.jar --flashpilot.clinic.pay-minutes=1
  .\scripts\soak-expiry.ps1 -Stock 60000 -LoadSeconds 30 -WatchMinutes 6
#>
param(
    [long]   $ScheduleId   = 1001,
    [int]    $Stock        = 60000,
    [int]    $Concurrency  = 200,
    [int]    $LoadSeconds  = 30,
    [int]    $WatchMinutes = 6,
    [string] $BaseUrl      = "http://127.0.0.1:8090"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

function Section($t) {
    Write-Host ""
    Write-Host "=== $t ===" -ForegroundColor Cyan
}

function Get-State {
    try {
        $s = Invoke-RestMethod -Uri "$BaseUrl/seckill/state/$ScheduleId" -TimeoutSec 5
        return @{
            bucketSum  = [int]$s.bucketSum
            leaseHeld  = [int]$s.leaseHeldAllInstances
            localRem   = [int]$s.localRemaining
        }
    } catch { return $null }
}

function Get-Check {
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/verify/check" -TimeoutSec 30
        return @{
            initial   = [int]$r.initialStock
            bucketSum = [int]$r.bucketSum
            leaseHeld = [int]$r.leaseHeld
            holding   = [int]$r.orderCount
            vanished  = [int]$r.vanished
            passed    = [bool]$r.passed
        }
    } catch { return $null }
}

# ---------------------------------------------------------------------------
Section "确认支付时限已调短"
# ---------------------------------------------------------------------------
# 这一步不能跳过。用默认的 10 分钟跑，观察窗口内一张单都不会到期，
# 脚本会输出一堆「号源没回来」，而那不是缺陷，是配置没生效。
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
    if ($health.status -ne "UP") { throw "服务不健康：$($health.status)" }
} catch {
    Write-Host "服务不可用：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 抢一个号，读它的支付时限，反推 pay-minutes 实际生效的值
$probeHolder = 999000001
try {
    Invoke-RestMethod -Uri "$BaseUrl/verify/preheat?poolId=$ScheduleId&totalStock=100&buckets=8" -Method Post -TimeoutSec 30 | Out-Null
    Invoke-RestMethod -Uri "$BaseUrl/seckill/$ScheduleId`?holderId=$probeHolder" -Method Post -TimeoutSec 10 | Out-Null
    Start-Sleep -Seconds 2
    $appts = Invoke-RestMethod -Uri "$BaseUrl/clinic/appointments?patientId=$probeHolder&limit=1" -TimeoutSec 10
    if (-not $appts -or $appts.Count -eq 0) { throw "探测单没落库" }
    $deadlineMs = [long]$appts[0].payDeadlineMs
    $nowMs = [long](([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())
    $minutes = [math]::Round(($deadlineMs - $nowMs) / 60000.0, 1)
    Write-Host ("  实测支付时限 ≈ {0} 分钟" -f $minutes)
    if ($minutes -gt ($WatchMinutes - 1)) {
        Write-Host ""
        Write-Host "  支付时限（$minutes 分钟）相对观察窗口（$WatchMinutes 分钟）太长了。" -ForegroundColor Yellow
        Write-Host "  单子不会在窗口内到期，这一轮什么都测不到。" -ForegroundColor Yellow
        Write-Host "  用短支付时限重启应用：" -ForegroundColor Yellow
        Write-Host "    java -jar target\flashpilot-0.1.0.jar --flashpilot.clinic.pay-minutes=1" -ForegroundColor White
        exit 1
    }
} catch {
    Write-Host "  探测失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
Section "重置并攒单"
# ---------------------------------------------------------------------------
Invoke-RestMethod -Uri "$BaseUrl/verify/preheat?poolId=$ScheduleId&totalStock=$Stock&buckets=8" -Method Post -TimeoutSec 60 | Out-Null
Write-Host ("  号池 {0} 重置为 {1} 个号" -f $ScheduleId, $Stock)

# 等指标反映出 preheat（指标是 1 秒一轮的定时采集，不是实时读 Redis）
for ($i = 0; $i -lt 20; $i++) {
    $st = Get-State
    if ($st -and $st.bucketSum -eq $Stock) { break }
    Start-Sleep -Milliseconds 250
}

# 用 target/classes 而不是 jar：jar 里的类被 Spring Boot 的嵌套布局包着，
# 直接 -cp 跑不了。run-experiment.ps1 也是这么做的。
$classes = Join-Path (Split-Path $PSScriptRoot -Parent) "target\classes"
if (-not (Test-Path (Join-Path $classes "com\flashpilot\tools\LoadGenerator.class"))) {
    Write-Host "  找不到 LoadGenerator.class，先跑 mvn compile" -ForegroundColor Red
    exit 1
}

Write-Host ("  压测 {0} 秒 / {1} 并发..." -f $LoadSeconds, $Concurrency)
$loadOut = & java -cp $classes com.flashpilot.tools.LoadGenerator `
    --url $BaseUrl --item $ScheduleId --concurrency $Concurrency `
    --duration $LoadSeconds --profile constant 2>&1 | Out-String

$sold = 0
if ($loadOut -match '成功（成交）\s+(\d+)') { $sold = [int]$Matches[1] }
Write-Host ("  压测结束，客户端观测成交 {0}" -f $sold)

# 等 Stream 追平，否则「待支付单数」还在涨，基线就不稳
Write-Host "  等消费者追平…"
for ($i = 0; $i -lt 120; $i++) {
    $c = Get-Check
    if ($c -and $c.holding -gt 0) {
        Start-Sleep -Seconds 2
        $c2 = Get-Check
        if ($c2 -and $c2.holding -eq $c.holding) { break }
    } else {
        Start-Sleep -Seconds 2
    }
}

$base = Get-Check
if (-not $base) { Write-Host "  校验接口不可用" -ForegroundColor Red; exit 1 }
Write-Host ""
Write-Host ("  攒下待支付单 {0} 张；桶余量 {1}；实例持有 {2}" -f $base.holding, $base.bucketSum, $base.leaseHeld)
Write-Host ("  等式③ 残差 {0}" -f $base.vanished)
$pending0 = $base.holding

if ($pending0 -lt 100) {
    Write-Host ""
    Write-Host "  待支付单太少（$pending0 张），压不出量级问题。加大 -Stock 或 -LoadSeconds。" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
Section "观察号源怎么回来（$WatchMinutes 分钟）"
# ---------------------------------------------------------------------------
Write-Host "  压测已经停了。下面每 5 秒采一次，看的全是超时释放这条路径。"
Write-Host ""
Write-Host ("  {0,-8} {1,8} {2,8} {3,9} {4,8} {5,10}" -f "时刻", "待支付", "桶余量", "释放/秒", "残差", "桶>总量")

$samples = New-Object System.Collections.Generic.List[object]
$deadline = (Get-Date).AddMinutes($WatchMinutes)
$prevPending = $pending0
$prevAt = Get-Date
$maxBucket = 0
$oversoldSeen = $false
$firstDrainAt = $null
$drainedAt = $null

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $c = Get-Check
    if (-not $c) { continue }

    $now = Get-Date
    $dt = ($now - $prevAt).TotalSeconds
    $rate = if ($dt -gt 0) { [math]::Round(($prevPending - $c.holding) / $dt, 1) } else { 0 }

    if ($c.bucketSum -gt $maxBucket) { $maxBucket = $c.bucketSum }
    # 桶余量超过总号数 = 归还多于发出，这就是超卖的直接体征
    $over = $c.bucketSum -gt $c.initial
    if ($over) { $oversoldSeen = $true }

    if ($null -eq $firstDrainAt -and $c.holding -lt $pending0) { $firstDrainAt = $now }
    if ($null -eq $drainedAt -and $c.holding -eq 0 -and $pending0 -gt 0) { $drainedAt = $now }

    $line = "  {0,-8} {1,8} {2,8} {3,9} {4,8} {5,10}" -f `
        $now.ToString("HH:mm:ss"), $c.holding, $c.bucketSum, $rate, $c.vanished, $(if ($over) { "是!" } else { "否" })
    if ($over) { Write-Host $line -ForegroundColor Red }
    elseif ($rate -gt 0) { Write-Host $line -ForegroundColor Gray }
    else { Write-Host $line -ForegroundColor DarkGray }

    $samples.Add([pscustomobject]@{
        ts = $now.ToString("HH:mm:ss.fff"); pending = $c.holding; bucketSum = $c.bucketSum
        leaseHeld = $c.leaseHeld; vanished = $c.vanished; rate = $rate; overTotal = $over
    })

    $prevPending = $c.holding
    $prevAt = $now

    if ($c.holding -eq 0 -and $pending0 -gt 0 -and $drainedAt -and ($now - $drainedAt).TotalSeconds -gt 20) {
        Write-Host "  待支付已清零并稳定，提前结束观察" -ForegroundColor Green
        break
    }
}

$csv = Join-Path $env:TEMP ("fp-soak-" + (Get-Random) + ".csv")
$samples | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8

# ---------------------------------------------------------------------------
Section "结论"
# ---------------------------------------------------------------------------
$final = Get-Check
$releasedTotal = $pending0 - $final.holding

Write-Host ("  攒下待支付        {0}" -f $pending0)
Write-Host ("  已释放            {0}" -f $releasedTotal)
Write-Host ("  仍待支付          {0}" -f $final.holding)
Write-Host ("  桶余量峰值        {0}  (总号数 {1})" -f $maxBucket, $final.initial)
Write-Host ("  最终残差          {0}" -f $final.vanished)
Write-Host ("  采样曲线          {0}" -f $csv)

if ($firstDrainAt -and $drainedAt) {
    $span = [math]::Round(($drainedAt - $firstDrainAt).TotalSeconds, 1)
    if ($span -gt 0) {
        Write-Host ("  排空耗时          {0} 秒（均速 {1} 个/秒）" -f $span, [math]::Round($pending0 / $span, 1))
    }
    # 有积压时释放任务会连续跑满 10 批才让出线程（不再每批之后空等 3 秒），
    # 所以上限 ≈ 5000 个 / (10 × 单批耗时 + 3 秒)。单批 500 个实测约 1.3 秒 → 约 320 个/秒。
    # 远低于此说明每张单的归还开销过大（每张是 2 次 MySQL + 1 次 Redis Lua + 1 次判重清理）。
    Write-Host "  参考量级          约 320 个/秒（积压时连续 10 批，单批 500 约 1.3 秒）"
    Write-Host "                    修「有积压仍空等 3 秒」之前只有 117 个/秒" -ForegroundColor DarkGray
}

Write-Host ""
$bad = $false
if ($oversoldSeen -or $maxBucket -gt $final.initial) {
    Write-Host "  ✘ 桶余量超过总号数 —— 号源被重复归还了，这是超卖。" -ForegroundColor Red
    Write-Host "    查 markExpired 的条件更新是否真的在挡重复处理（支付与超时释放的竞争）。" -ForegroundColor Red
    $bad = $true
}
if ($final.vanished -gt 0) {
    Write-Host ("  ✘ 残差 +{0} —— 有号没回到桶里（少卖）。查「号源归还失败」日志。" -f $final.vanished) -ForegroundColor Red
    $bad = $true
} elseif ($final.vanished -lt 0) {
    Write-Host ("  ✘ 残差 {0} —— 占号比总号数多，潜在超卖。" -f $final.vanished) -ForegroundColor Red
    $bad = $true
}
if ($final.holding -gt 0 -and $releasedTotal -eq 0) {
    Write-Host "  ✘ 一张单都没释放。支付时限可能没到，或释放任务没在跑。" -ForegroundColor Red
    $bad = $true
}
if (-not $bad) {
    Write-Host "  ✔ 号源全部原样回到桶里：没有重复归还，没有漏释放，残差归零。" -ForegroundColor Green
    Write-Host "    这条路径以前从没在量级上跑过 —— 所有压测都短于支付时限。" -ForegroundColor DarkGray
}
