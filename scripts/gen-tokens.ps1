<#
.SYNOPSIS
    预生成一批患者令牌，给 wrk 这类不方便算 HMAC 的压测工具用。

.DESCRIPTION
    抢号接口的身份来自 X-Patient-Token（HMAC 签名），而 wrk 的 Lua 里没有
    现成的 HMAC-SHA256。所以换个做法：**预生成一个令牌池，压测时轮着用** ——
    真实压测本来就这么干，因为把认证握手算进压测本身会污染延迟数据。

    用法：
        $env:PATIENT_TOKEN_SECRET = "bench-secret"
        ./scripts/gen-tokens.ps1 -Count 200000 -OutFile bench-tokens.txt
        wrk -t8 -c400 -d30s -s scripts/wrk-seckill.lua http://127.0.0.1:8090
#>
param(
    [int]    $Count   = 200000,
    [long]   $StartId = 1,
    [string] $OutFile = "bench-tokens.txt"
)

. "$PSScriptRoot/lib/PatientToken.ps1"

$secret = Get-PatientSecret
Write-Host "生成 $Count 个令牌（患者 ID $StartId..$($StartId + $Count - 1)）→ $OutFile"

# 复用同一个 HMAC 实例：20 万次 new/Dispose 会明显拖慢，而这里只是个工具脚本，
# 但慢到几十秒就会有人以为它卡死了。
$mac = [System.Security.Cryptography.HMACSHA256]::new(
    [System.Text.Encoding]::UTF8.GetBytes($secret))
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    # StreamWriter 而不是 Add-Content：后者每行都开关一次文件句柄，
    # 20 万行要跑好几分钟。
    $w = [System.IO.StreamWriter]::new(
        [System.IO.Path]::GetFullPath($OutFile), $false,
        (New-Object System.Text.UTF8Encoding($false)))
    try {
        for ($i = 0; $i -lt $Count; $i++) {
            $id  = $StartId + $i
            $raw = $mac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes([string]$id))
            $b64 = [Convert]::ToBase64String($raw).TrimEnd('=').Replace('+', '-').Replace('/', '_')
            $w.WriteLine("$id.$b64")
        }
    } finally {
        $w.Dispose()
    }
} finally {
    $mac.Dispose()
}
$sw.Stop()
Write-Host ("完成：$OutFile（{0} 行，耗时 {1:N1} 秒）" -f $Count, $sw.Elapsed.TotalSeconds) -ForegroundColor Green
Write-Host "注意服务端必须用同一个 PATIENT_TOKEN_SECRET 启动，否则全部 401。" -ForegroundColor DarkGray
