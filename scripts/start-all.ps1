<#
.SYNOPSIS
    起基础设施并等到真正可用，然后告诉你下一步做什么。

.DESCRIPTION
    docker compose up 返回成功 ≠ MySQL 已经能连。这个脚本会等到 healthcheck 通过为止，
    避免应用启动时因为数据库还在初始化而报一堆看不懂的错。

.EXAMPLE
    .\start-all.ps1
    .\start-all.ps1 -WithObservability
#>
param(
    [switch] $WithObservability
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

Write-Host "=== 检查 Docker 守护进程 ===" -ForegroundColor Cyan
$dockerOk = $false
try {
    docker info 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $dockerOk = $true }
} catch { }

if (-not $dockerOk) {
    Write-Host "Docker 守护进程没在跑。" -ForegroundColor Red
    Write-Host ""
    Write-Host "请手动启动 Docker Desktop（启动它的后台服务需要管理员权限，脚本代劳不了）：" -ForegroundColor Yellow
    Write-Host "  1. 开始菜单搜索 Docker Desktop 并打开"
    Write-Host "  2. 如果弹 UAC 确认框，点是"
    Write-Host "  3. 等右下角托盘图标变成稳定的鲸鱼（约 1-2 分钟）"
    Write-Host "  4. 回来重新跑这个脚本"
    exit 1
}
Write-Host "Docker 就绪" -ForegroundColor Green

Write-Host ""
Write-Host "=== 启动 redis + mysql ===" -ForegroundColor Cyan
if ($WithObservability) {
    docker compose --profile obs up -d
} else {
    docker compose up -d
}

Write-Host ""
Write-Host "=== 等 healthcheck 通过 ===" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    $redisState = (docker inspect -f '{{.State.Health.Status}}' fp-redis 2>$null)
    $mysqlState = (docker inspect -f '{{.State.Health.Status}}' fp-mysql 2>$null)
    Write-Host ("  redis=$redisState  mysql=$mysqlState") -ForegroundColor DarkGray
    if ($redisState -eq "healthy" -and $mysqlState -eq "healthy") { $ready = $true; break }
}

if (-not $ready) {
    Write-Host "等待超时。看一下日志：docker compose logs --tail 50" -ForegroundColor Red
    exit 1
}
Write-Host "基础设施就绪" -ForegroundColor Green

Write-Host ""
Write-Host "=== 确认建表脚本已执行 ===" -ForegroundColor Cyan
# 用 MYSQL_PWD 传密码，而不是 -p 参数。
# 原因：-p 会让 mysql 客户端往 stderr 打「命令行传密码不安全」的警告，
# 而 PowerShell 5.1 在重定向原生命令 stderr 时会把每一行包成 ErrorRecord 并把 $? 置为 false，
# 于是一个纯粹的警告会让整个脚本判定失败。这个坑很隐蔽，别改回去。
$tables = docker exec -e MYSQL_PWD=flashpilot fp-mysql mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='flashpilot';"
Write-Host ("flashpilot 库里有 $tables 张表（应该是 4 张）")
if ([int]$tables -lt 4) {
    Write-Host "表不全。建表脚本只在数据卷首次创建时执行，需要重来：" -ForegroundColor Yellow
    Write-Host "  docker compose down -v; .\scripts\start-all.ps1" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "─────────────────────────────────────────" -ForegroundColor Cyan
Write-Host " 下一步" -ForegroundColor Cyan
Write-Host "─────────────────────────────────────────" -ForegroundColor Cyan
Write-Host "  1. 启动应用      mvn spring-boot:run"
Write-Host "  2. 另开一个终端  mvn compile"
Write-Host "  3. 跑第一轮实验  .\scripts\run-experiment.ps1"
if ($WithObservability) {
    Write-Host ""
    Write-Host "  Grafana     http://localhost:3000"
    Write-Host "  Prometheus  http://localhost:9090"
}
