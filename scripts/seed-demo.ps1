# 演示数据一键就绪：灌 SQL + 走真实放号链路把号推进 Redis。
#
# 为什么要两步：sql/04-demo-data.sql 只建排班（status=PENDING、released_slots=0），
# 号还没进 Redis 桶。放号是「先在 MySQL 预留进度、再推进 Redis」的两步操作
# （ReleaseService.releaseBatch），只灌 SQL 等于只做了一半，
# 患者会全部收到「号源已满」而看板显示放号 100% —— 典型的假成功。
#
# 用法：  .\scripts\seed-demo.ps1
#         .\scripts\seed-demo.ps1 -BaseUrl http://localhost:8090

param(
    [string]$BaseUrl = "http://localhost:8090",
    [string]$MysqlContainer = "fp-mysql",
    [string]$RedisContainer = "fp-redis",
    [string]$MysqlPassword = "flashpilot"
)

$ErrorActionPreference = "Continue"
# 注意用 UTF8Encoding($false) 而不是 [Text.Encoding]::UTF8 ——
# 后者带 BOM 前导码，管道喂给容器里的 mysql 时 BOM 会被当成 SQL 的第一个字符，
# 直接报 "ERROR 1064 ... near '﻿--'"。这个坑很难看出来，因为报错指向的
# 第一行注释本身完全正确，肉眼看不到那 3 个字节。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

# PowerShell 5.1 的 Invoke-RestMethod 拿到 JSON 数组时，会把整个数组当成<b>一个</b>对象
# 交给下游，而不是逐个展开。于是 `foreach ($x in $resp)` 只循环一次，
# 循环体里的 $x.id 是「所有 id 组成的数组」，$x.id -lt 20000 这类比较会返回
# 过滤后的数组（非空即为真），条件判断全部失效 —— 循环看起来跑完了、一件事没做。
#
# 这个坑我在验证脚本里连踩两次：现象是"已放号 0 个、跳过 0 个"，
# 既没有成功也没有失败，**没有任何错误信息指向真正的原因**。
# 所以统一用这个函数把响应摊平，不直接 foreach 响应对象。
function AsList($resp) {
    $out = New-Object System.Collections.ArrayList
    foreach ($item in @($resp)) {
        if ($item -is [System.Array]) { foreach ($sub in $item) { [void]$out.Add($sub) } }
        elseif ($null -ne $item)      { [void]$out.Add($item) }
    }
    return $out
}

Write-Host ""
Write-Host "=== 1/4 灌演示排班 ===" -ForegroundColor Cyan
$sqlFile = Join-Path $root "sql\04-demo-data.sql"
if (-not (Test-Path $sqlFile)) { Write-Host "找不到 $sqlFile" -ForegroundColor Red; exit 1 }
# 用 stdin 喂给容器里的 mysql，避免把密码写进命令行。
# 再显式剥一次 BOM：文件本身可能是带 BOM 存的（编辑器默认行为），
# 上面的 $OutputEncoding 只管 PowerShell 自己加不加，管不了文件里原本有没有。
$sqlText = [System.IO.File]::ReadAllText($sqlFile).TrimStart([char]0xFEFF)
$sqlText | docker exec -i -e MYSQL_PWD=$MysqlPassword $MysqlContainer `
        mysql -uroot flashpilot --default-character-set=utf8mb4
if ($LASTEXITCODE -ne 0) { Write-Host "SQL 执行失败，检查容器 $MysqlContainer 是否在跑" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "=== 2/4 确认放号节奏 ===" -ForegroundColor Cyan
# 演示数据要立刻可约，所以先把分批放号关掉（spreadSeconds=0 表示一次放完）。
# 想演示分批削峰时把它调回去，再对某个排班重新 open 即可。
$r = Invoke-RestMethod "$BaseUrl/control/config?param=release.spreadSeconds&value=0&reason=演示数据一次性放号" -Method Post
Write-Host ("  release.spreadSeconds -> {0}  ({1})" -f $r.appliedValue, $r.note)

$targetsPre = AsList (Invoke-RestMethod "$BaseUrl/admin/schedules")

Write-Host ""
Write-Host "=== 3/4 清空演示排班的 Redis 号池 ===" -ForegroundColor Cyan
# **这一步不能省。** sql/04 把 released_slots 重置成 0，而 Redis 桶里上一次放的号
# 还在 —— 于是每重灌一次就往桶里多堆一份。实测灌 4 次之后：
#   排班 20009 MySQL 总号数 40，Redis 桶里 140 个；
#   抢 60 次全部返回 code=200，而 MySQL 只落库 40 条 ——
#   **20 个患者收到「抢号成功」却拿不到预约单。**
#
# MySQL 的 `booked_slots + ? <= total_slots` 兜底挡住了真超卖（这是它存在的意义，
# 且 oversoldBlocked 如实计了 20），但**兜底不该是常态**。
# 根因是这个脚本让两个存储发散：清一边不清另一边。
#
# 教训和 bug ⑰（放号先推 Redis 再记账）同源：
# **两个存储的重置必须成对，否则"可重复执行"就只对其中一个成立。**
$demoIds = ($targetsPre | Where-Object { $_.scheduleId -ge 20000 } | ForEach-Object { $_.scheduleId })
$cleared = 0
foreach ($id in $demoIds) {
    # 桶 + 租约 + 判重标记一起清，和 preheat 的语义保持一致
    $keys = @()
    0..31 | ForEach-Object { $keys += "sk:item:$id`:b:$_" }
    $keys += "sk:item:$id`:lease"
    $keys += "sk:item:$id`:bought"
    docker exec $RedisContainer redis-cli DEL @keys | Out-Null
    $cleared++
}
Write-Host ("  已清空 {0} 个演示排班的号池（桶/租约/判重）" -f $cleared)

Write-Host ""
Write-Host "=== 4/4 走真实放号链路把号推进 Redis ===" -ForegroundColor Cyan
$targets = $targetsPre
$ok = 0; $skip = 0
# 字段名是 scheduleId 不是 id —— /admin/schedules 返回的是给运营看的视图，
# 主键叫 scheduleId。写成 $s.id 会取到 $null，而 $null -lt 20000 在 PowerShell 里
# 求值为 $true，于是每一条都被 continue 跳过：
# **输出"已放号 0 个、跳过 0 个"，既不报错也不做事。**
# 和之前 createSchedule 不返回 id 那个 bug 是同一类：字段名错了但没有任何东西报错。
foreach ($s in $targets) {
    if ($null -eq $s.scheduleId -or $s.scheduleId -lt 20000) { continue }   # 跳过压测号池
    try {
        $res = Invoke-RestMethod "$BaseUrl/admin/schedules/$($s.scheduleId)/open" -Method Post -TimeoutSec 20
        if ($res.ok) { $ok++ } else { $skip++; Write-Host ("  跳过 {0}：{1}" -f $s.scheduleId, $res.message) -ForegroundColor DarkGray }
    } catch {
        $skip++
        Write-Host ("  失败 {0}：{1}" -f $s.scheduleId, $_.Exception.Message) -ForegroundColor Yellow
    }
}
if ($ok -eq 0) {
    # 一个都没放成必定是有问题的（演示排班刚建好、必然处于待放号状态）。
    # 显式喊出来，不要让"0 个"混在正常输出里被当成成功。
    Write-Host "  !! 一个排班都没放号成功，检查 /admin/schedules 的返回字段和排班状态" -ForegroundColor Red
}
Write-Host ("  已放号 {0} 个排班，跳过 {1} 个" -f $ok, $skip)

Write-Host ""
Write-Host "=== 结果 ===" -ForegroundColor Cyan
$tomorrow = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
foreach ($deptId in 1, 2, 3) {
    $list = AsList (Invoke-RestMethod "$BaseUrl/clinic/schedules?departmentId=$deptId&date=$tomorrow")
    foreach ($x in $list) {
        Write-Host ("  {0,-6} {1,-5} {2,-7} {3}  余 {4}/{5}" -f `
            $x.doctorName, $x.period, $x.slotType, $x.visitStart, ($x.totalSlots - $x.bookedSlots), $x.totalSlots)
    }
}
Write-Host ""
Write-Host "打开 $BaseUrl 即可挂号（默认查明天 $tomorrow）" -ForegroundColor Green
Write-Host ""