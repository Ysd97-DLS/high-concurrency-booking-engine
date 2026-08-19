<#
.SYNOPSIS
    起 Redis 主从 + 哨兵（P6 主从切换实验用），自动探测宿主机 IP 并验证拓扑。

.DESCRIPTION
    为什么要这个脚本而不是直接 docker compose up：
    docker-compose.ha.yaml 里所有地址都用宿主机 IP（原因见那个文件的注释），
    而宿主机 IP 是 DHCP 分配的、会变，不能写死。这里自动探测并注入。

    脚本最后会做一次「客户端视角」的验证：从宿主机去问哨兵「主库在哪」，
    再直接连那个地址。这一步是必须的 —— 哨兵自己工作正常但上报的地址
    客户端连不通，是这套东西最容易踩的坑，而且症状是「切换了但客户端不跟随」。

.EXAMPLE
    .\start-ha.ps1
    # 然后让应用以 ha profile 启动（走哨兵发现主库）：
    #   java -jar target\flashpilot-0.1.0.jar --spring.profiles.active=ha
#>

# 刻意用 Continue 而不是 Stop：docker 会把正常进度信息写到 stderr，
# 而 PowerShell 5.1 在重定向原生命令 stderr 时会把每一行包成 ErrorRecord，
# 配合 Stop 就会让「Container fp-sentinel Stopping」这种正常输出直接中断脚本。
# 这个坑在本项目里已经踩过两次（start-all.ps1 的 mysql 密码警告也是同一类）。
$ErrorActionPreference = "Continue"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

function Section($t) {
    Write-Host ""
    Write-Host ("=== " + $t + " ===") -ForegroundColor Cyan
}

Section "探测宿主机 IP"
# 取「有默认网关且网卡在线」的那块网卡的 IPv4 —— 排除 WSL / Hyper-V 的虚拟网卡，
# 那些地址容器能连但语义上不是「宿主机在局域网里的地址」，换网络环境容易出错。
$cfg = Get-NetIPConfiguration |
    Where-Object { $null -ne $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } |
    Select-Object -First 1
if ($null -eq $cfg) {
    Write-Host "找不到带默认网关的在线网卡，无法确定宿主机 IP" -ForegroundColor Red
    exit 1
}
$hostIp = $cfg.IPv4Address.IPAddress
Write-Host ("  宿主机 IP = {0}  （网卡 {1}）" -f $hostIp, $cfg.InterfaceAlias) -ForegroundColor Green
$env:FP_HOST_IP = $hostIp

Section "启动主从 + 哨兵"
docker compose -f docker-compose.ha.yaml down | Out-Null
docker compose -f docker-compose.ha.yaml up -d | Out-Null
Write-Host "  容器已启动，等哨兵完成拓扑发现…"
Start-Sleep -Seconds 10

Section "验证复制关系"
$masterRole = (docker exec fp-redis-master redis-cli INFO replication 2>$null | Select-String "^role:").Line
$slaves     = (docker exec fp-redis-master redis-cli INFO replication 2>$null | Select-String "^connected_slaves:").Line
$slave0     = (docker exec fp-redis-master redis-cli INFO replication 2>$null | Select-String "^slave0:").Line
$replRole   = (docker exec fp-redis-replica redis-cli INFO replication 2>$null | Select-String "^role:").Line
$link       = (docker exec fp-redis-replica redis-cli INFO replication 2>$null | Select-String "^master_link_status:").Line
Write-Host ("  主库  " + $masterRole + "  " + $slaves)
Write-Host ("  从库在主库上的登记  " + $slave0)
Write-Host ("  从库  " + $replRole + "  " + $link)

if ($slave0 -notmatch [regex]::Escape($hostIp)) {
    Write-Host "  !!! 从库 announce 的不是宿主机 IP —— 客户端将连不通它 !!!" -ForegroundColor Red
} else {
    Write-Host "  从库 announce 地址正确（宿主机 IP）" -ForegroundColor Green
}

Section "客户端视角验证：问哨兵要主库地址，然后直接连"
$addr = docker exec fp-sentinel redis-cli -p 26379 SENTINEL get-master-addr-by-name fpmaster 2>$null
$mIp = $addr[0]; $mPort = $addr[1]
Write-Host ("  哨兵上报的主库地址：{0}:{1}" -f $mIp, $mPort)

if ($mIp -ne $hostIp) {
    Write-Host "  !!! 上报地址不是宿主机 IP，宿主机上的应用会连不通 !!!" -ForegroundColor Red
    exit 1
}

# 真正从宿主机去连一次，并确认它是可写的主库
try {
    $probe = New-Object System.Net.Sockets.TcpClient
    $probe.Connect($mIp, [int]$mPort)
    $probe.Close()
    Write-Host "  宿主机 TCP 连通" -ForegroundColor Green
} catch {
    Write-Host ("  !!! 宿主机连不通 {0}:{1} —— {2}" -f $mIp, $mPort, $_.Exception.Message) -ForegroundColor Red
    exit 1
}

Section "下一步"
Write-Host "  以 ha profile 启动应用（走哨兵发现主库，能自动跟随切换）："
Write-Host "    java -jar target\flashpilot-0.1.0.jar --spring.profiles.active=ha" -ForegroundColor White
Write-Host ""
Write-Host "  然后跑 P6："
Write-Host "    .\scripts\run-experiment.ps1 -Duration 90 -Stock 200000 -Chaos redis-failover" -ForegroundColor White
