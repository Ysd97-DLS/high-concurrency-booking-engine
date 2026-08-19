<#
.SYNOPSIS
    故障注入。压测跑通只能证明「顺利的时候没问题」，这个脚本负责制造不顺利。

.DESCRIPTION
    对应实验方案里的 P4/P5/P6：
      mysql-pause    冻结 MySQL 容器 → 消费端卡住 → 观察 Stream 积压、控制面是否主动降速
      redis-pause    冻结 Redis → 观察领号段失败时数据面是否优雅降级
      kill-app       强杀一个应用实例（等价于 kill -9）→ 观察少卖与租约回收
      redis-failover 主从切换 → 观察异步复制丢写对库存的影响
    docker pause 比 toxiproxy 简单得多，效果也够：它直接冻结容器里的所有进程。

.EXAMPLE
    .\chaos.ps1 -Action mysql-pause -Seconds 8
    .\chaos.ps1 -Action kill-app -Port 8081
    .\chaos.ps1 -Action redis-failover
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("status", "mysql-pause", "mysql-resume", "redis-pause", "redis-resume",
                 "kill-app", "redis-failover")]
    [string] $Action,

    # pause 类动作自动恢复前等待的秒数；填 0 表示不自动恢复
    [int]    $Seconds = 8,

    # kill-app 用：要杀掉的实例监听的端口
    [int]    $Port = 8081
)

$ErrorActionPreference = "Continue"

function Pause-Container($name, $seconds) {
    Write-Host "冻结容器 $name …" -ForegroundColor Yellow
    docker pause $name
    if ($seconds -gt 0) {
        Write-Host "保持 $seconds 秒（这段时间去看 /control/metrics 和 Grafana）" -ForegroundColor DarkGray
        Start-Sleep -Seconds $seconds
        docker unpause $name
        Write-Host "已恢复 $name" -ForegroundColor Green
    } else {
        Write-Host "未自动恢复，记得手工 docker unpause $name" -ForegroundColor Yellow
    }
}

switch ($Action) {

    "status" {
        Write-Host "=== 容器状态 ===" -ForegroundColor Cyan
        docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
        Write-Host ""
        Write-Host "=== 监听中的应用端口 ===" -ForegroundColor Cyan
        Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -ge 8080 -and $_.LocalPort -le 8090 } |
            Select-Object LocalPort, OwningProcess |
            Format-Table -AutoSize
    }

    "mysql-pause"  { Pause-Container "fp-mysql" $Seconds }
    "mysql-resume" { docker unpause fp-mysql; Write-Host "已恢复 fp-mysql" -ForegroundColor Green }
    "redis-pause"  { Pause-Container "fp-redis" $Seconds }
    "redis-resume" { docker unpause fp-redis; Write-Host "已恢复 fp-redis" -ForegroundColor Green }

    "kill-app" {
        # 找出占用该端口的进程并强杀 —— 等价于 kill -9，不给优雅下线的机会。
        # 这一步的目的就是绕过 @PreDestroy 的归还逻辑，逼出「少卖」。
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if ($null -eq $conn) {
            Write-Host "端口 $Port 上没有监听中的进程" -ForegroundColor Red
            exit 1
        }
        $pidToKill = $conn.OwningProcess

        # 校验一下要杀的是不是真的 java 进程。压测期间这个端口上有几百条 ESTABLISHED 连接，
        # 万一枚举拿错了对象，杀错进程比不杀更糟。
        $target = Get-Process -Id $pidToKill -ErrorAction SilentlyContinue
        if ($null -eq $target -or $target.ProcessName -ne "java") {
            Write-Host ("端口 $Port 上的 PID=$pidToKill 不是 java 进程（是 {0}），拒绝执行" -f $target.ProcessName) -ForegroundColor Red
            exit 1
        }
        Write-Host "强杀端口 $Port 上的进程 PID=$pidToKill（不走优雅下线）" -ForegroundColor Yellow
        Stop-Process -Id $pidToKill -Force -ErrorAction Continue

        # ---------- 校验：必须按「可观测行为」判定，不能信 Get-Process ----------
        #
        # 踩过两次同一个坑：Stop-Process 执行了、没报错，Get-Process -Id 也返回 null，
        # 于是脚本报告「已确认退出」—— 但目标进程的日志显示它又活了 15 秒，
        # 整轮 P5 的数据全是无效的，而报告上写着「全部等式通过」。
        #
        # 故障注入实验里，「故障没注入成功」是最坏的失败模式：它伪装成成功。
        # 所以判据换成我们真正在意的东西：端口不再监听 + 健康检查不可达。
        # 这两条是压测流量实际感受到的，比进程表更可信。
        function Test-AppDown($port) {
            $stillListening = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            if ($stillListening) { return $false }
            try {
                Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2 -UseBasicParsing | Out-Null
                return $false      # 还能应答，没死
            } catch { return $true }
        }

        $down = $false
        for ($k = 0; $k -lt 24; $k++) {
            Start-Sleep -Milliseconds 250
            if (Test-AppDown $Port) { $down = $true; break }
            # 2 秒还没下去就上 taskkill 兜底（比 Stop-Process 更硬）
            if ($k -eq 8) {
                Write-Host "  Stop-Process 未生效，改用 taskkill /F" -ForegroundColor Yellow
                & taskkill /F /PID $pidToKill 2>&1 | ForEach-Object { Write-Host ("    " + $_) -ForegroundColor DarkGray }
            }
        }
        if ($down) {
            Write-Host "已确认端口 $Port 不再服务（进程真的下去了）" -ForegroundColor Green
        } else {
            Write-Host "!!! 端口 $Port 6 秒后仍在服务 —— 故障注入失败，本轮实验数据无效，不要采信 !!!" -ForegroundColor Red
            exit 1
        }
        Write-Host ""
        Write-Host "接下来观察：" -ForegroundColor Cyan
        Write-Host "  1) 这个实例手里的本地号段现在处于「无人认领」状态"
        Write-Host "  2) 租约 10 秒后过期，其它实例的回收任务会把库存还回桶"
        Write-Host "  3) 看日志里的「回收过期租约」，以及 lease.reclaimed 指标"
        Write-Host "  4) 压完后 /verify/check 的等式③ 残差应该回到 0"
    }

    "redis-failover" {
        Write-Host "需要先起主从+哨兵：docker compose -f docker-compose.ha.yaml up -d" -ForegroundColor DarkGray
        Write-Host "强制主从切换…" -ForegroundColor Yellow
        docker exec fp-sentinel redis-cli -p 26379 sentinel failover fpmaster
        Write-Host ""
        Write-Host "接下来观察：" -ForegroundColor Cyan
        Write-Host "  1) 切换窗口内领号段会失败，数据面应该退化而不是报错崩掉"
        Write-Host "  2) Redis 主从是异步复制，未同步的写会丢 —— 这会体现为等式③ 的残差"
        Write-Host "  3) 这正是「不把 Redis 当账本」的理由：MySQL 才是权威，差额由对账补偿"
        docker exec fp-sentinel redis-cli -p 26379 sentinel get-master-addr-by-name fpmaster
    }
}
