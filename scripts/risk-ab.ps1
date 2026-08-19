<#
.SYNOPSIS
    风控 A/B 对照：正常患者组 vs 一机多号黄牛组。

.DESCRIPTION
    这个脚本存在的理由：**风控的好坏不能用高压画像测。**
    高压画像是「20 万患者各请求两三次」，在患者频次判据看来是全员高频，
    测出来的只是慢车道的速率，不是风控的识别能力。

    风控要回答的是「能不能把黄牛和真实患者分开」，那就必须有两组形态不同的流量：
      A 组：N 个患者，每人自己的设备，各请求 1 次   —— 正常挂号，应该全部通过
      B 组：M 个患者，全部来自同一个设备指纹        —— 一机多号批量代抢，应该被压制

    判据是两个数一起看，缺一个都不算通过：
      A 组成交率 ≈ 100%  且 A 组风控记录 = 0   （零误判，这条更重要）
      B 组成交率显著低于 A 组                   （有效压制）

    只看 B 组被压制是不够的：把阈值调到 0 也能压制 B 组，代价是 A 组一起死。
    **零误判和有效压制必须同时成立**，这才是风控的实际约束。

.EXAMPLE
    .\scripts\risk-ab.ps1
    .\scripts\risk-ab.ps1 -Normal 50 -Scalper 40 -Threshold 3
#>
param(
    [string] $BaseUrl   = "http://localhost:8090",
    [int]    $Normal    = 50,      # A 组患者数
    [int]    $Scalper   = 40,      # B 组患者数（同一台设备）
    [int]    $Threshold = 3,       # 风控频次阈值，用生产默认值
    [int]    $Slots     = 300      # 号池要足够大，否则"售罄"会混进结论
)

$ErrorActionPreference = "Continue"
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function GJ($url, $method = "GET") {
    $p = @{ Uri = $url; TimeoutSec = 25; UseBasicParsing = $true }
    if ($method -eq "POST") { $p.Method = "Post" }
    $r = Invoke-WebRequest @p
    return ([System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json)
}

# ---------- 准备干净的号池 ----------
Write-Host ""
Write-Host "=== 准备 ===" -ForegroundColor Cyan
# 借用压测号池：preheat 会清空它的历史预约、重置风控计数、把号灌进 Redis。
# 用压测池而不是新建排班，是因为 preheat 一次把「号池 + 风控 + 限流」全部归零，
# 手工建排班还要自己想着清风控，容易漏。
$pre = GJ "$BaseUrl/verify/preheat?poolId=1001&totalStock=$Slots&buckets=8" "POST"
Write-Host ("  号池 1001 重置：{0} 个号，风控计数已清零" -f $pre.totalStock)

# 记下风控事件表的当前最大 id：只统计本轮新增的事件。
#
# 这里踩过坑：第一版直接按 patientId 区间过滤，报出"A 组 89 条误判"——
# 而 A 组实际零降权。原因是 t_risk_event **不被 preheat 清空**（审计数据本就该留），
# 而之前压测的 holderId 空间正好是 100000~200000，和 A 组的编号撞上了。
# 教训：**对照实验必须划定自己的时间窗**，不能假设表是干净的。
# 注意 @() 包一层：PS 5.1 对<b>单元素</b> JSON 数组会直接返回那个对象而不是数组，
# 于是 $baseline.Count 是 $null、$baseline[0] 取不到 —— baseId 静默留在 0，
# 过滤条件 id > 0 对所有行都成立，等于没过滤。
# 这是本项目第三次被 PS 5.1 的数组语义坑到，一律用 @() 兜住。
$baseline = @(GJ "$BaseUrl/admin/risk/events?limit=1")
$baseId = if ($baseline.Count -gt 0 -and $null -ne $baseline[0].id) { [long]$baseline[0].id } else { 0 }
Write-Host ("  风控事件基线 id = {0}（只统计此后新增的）" -f $baseId)

$rt = GJ "$BaseUrl/control/config?param=riskcontrol.threshold&value=$Threshold&reason=风控AB对照" "POST"
Write-Host ("  风控阈值 = {0}" -f $rt.appliedValue)
# 主限流要放开，否则限流会把两组一起拒掉，测不出风控的区分度
$lq = GJ "$BaseUrl/control/config?param=limit.qps&value=200000&reason=风控AB对照：排除限流干扰" "POST"
Write-Host ("  主限流   = {0}  (放开，排除干扰)" -f $lq.appliedValue)

# ---------- A 组：正常患者，各自设备 ----------
Write-Host ""
Write-Host "=== A 组：$Normal 个正常患者，各自设备，每人 1 次 ===" -ForegroundColor Cyan
$aOk = 0; $aDemoted = 0; $aOther = 0
for ($i = 1; $i -le $Normal; $i++) {
    $pid2 = 100000 + $i
    $r = GJ "$BaseUrl/seckill/1001?holderId=$pid2&deviceId=phone-of-patient-$i" "POST"
    switch ($r.code) {
        200  { $aOk++ }
        4291 { $aDemoted++ }
        default { $aOther++ }
    }
}
Write-Host ("  成交 {0}/{1}   被降权丢弃 {2}   其它 {3}" -f $aOk, $Normal, $aDemoted, $aOther)

# ---------- B 组：一台设备代抢 ----------
Write-Host ""
Write-Host "=== B 组：$Scalper 个患者，全部来自同一台设备 ===" -ForegroundColor Cyan
$bOk = 0; $bDemoted = 0; $bOther = 0
for ($i = 1; $i -le $Scalper; $i++) {
    $pid2 = 200000 + $i
    $r = GJ "$BaseUrl/seckill/1001?holderId=$pid2&deviceId=scalper-single-device" "POST"
    switch ($r.code) {
        200  { $bOk++ }
        4291 { $bDemoted++ }
        default { $bOther++ }
    }
}
Write-Host ("  成交 {0}/{1}   被降权丢弃 {2}   其它 {3}" -f $bOk, $Scalper, $bDemoted, $bOther)

# ---------- 结论 ----------
Write-Host ""
Write-Host "=== 风控内部状态 ===" -ForegroundColor Cyan
$dash = GJ "$BaseUrl/admin/dashboard"
$rk = $dash.risk
Write-Host ("  降权判定 {0}  慢车道 通过 {1}/丢弃 {2}" -f $rk.demoted, $rk.slowLanePassed, $rk.slowLaneDropped)
Write-Host ("  CMS 噪声底 {0}  判据可信 {1}" -f $rk.cmsNoiseFloor, $rk.criteriaHealthy)

# 风控事件是攒批异步落库的，等一下再查，否则会漏掉刚产生的那批
Start-Sleep -Seconds 3
$events = @(GJ "$BaseUrl/admin/risk/events?limit=500")
$fresh = @($events | Where-Object { $_.id -gt $baseId })
$aVictims = @($fresh | Where-Object { $_.patientId -ge 100000 -and $_.patientId -lt 200000 }).Count
$bCaught  = @($fresh | Where-Object { $_.patientId -ge 200000 }).Count
Write-Host ("  本轮新增风控记录 {0} 条：A 组 {1} 条（应为 0）  B 组 {2} 条" -f $fresh.Count, $aVictims, $bCaught)
if ($bCaught -gt 0) {
    $sample = $fresh | Where-Object { $_.patientId -ge 200000 } | Select-Object -First 1
    Write-Host ("  样例：患者 {0} 设备 {1} {2}/{3} — {4}" -f `
        $sample.patientId, $sample.deviceId, $sample.level, $sample.action, $sample.reason)
}

$aRate = if ($Normal  -gt 0) { $aOk * 100.0 / $Normal }  else { 0 }
$bRate = if ($Scalper -gt 0) { $bOk * 100.0 / $Scalper } else { 0 }

Write-Host ""
Write-Host "=== 结论 ===" -ForegroundColor Cyan
Write-Host ("  A 组（正常）成交率 {0}%" -f [math]::Round($aRate, 1))
Write-Host ("  B 组（黄牛）成交率 {0}%" -f [math]::Round($bRate, 1))
Write-Host ("  压制幅度           {0} 个百分点" -f [math]::Round($aRate - $bRate, 1))

$noFalsePositive = ($aRate -ge 99) -and ($aVictims -eq 0)
$effective = ($aRate - $bRate) -ge 30

if ($noFalsePositive -and $effective) {
    Write-Host "  通过：零误判 + 有效压制" -ForegroundColor Green
} elseif (-not $noFalsePositive) {
    # 这个方向的失败更严重：宁可放过黄牛，也不能挡住急需就诊的患者
    Write-Host "  失败：A 组有误判 —— 正常患者被挡，这比放过黄牛严重得多" -ForegroundColor Red
} else {
    Write-Host "  失败：B 组没有被有效压制，风控形同虚设" -ForegroundColor Red
    Write-Host "        先查 CMS 噪声底是否已盖过阈值（判据可信应为 True）" -ForegroundColor Yellow
}
Write-Host ""