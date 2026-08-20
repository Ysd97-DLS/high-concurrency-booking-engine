<#
.SYNOPSIS
    压测/实验脚本用的身份工具。

.DESCRIPTION
    抢号接口的患者身份来自 HMAC 签名令牌（请求头 X-Patient-Token），
    不再是 ?holderId= 参数。所以脚本要拿着和服务端相同的密钥自己签。

    起因是一次自查实测到的两件事：
      · POST /seckill/20016?holderId=999999999 —— 编造的 ID 也能抢到号
      · 而风控的频次判据全部按这个 ID 计数，每次换一个就整套失效

    用法：
        . "$PSScriptRoot/lib/PatientToken.ps1"
        $tok = New-PatientToken -PatientId 5501
        Invoke-RestMethod -Uri $u -Method Post -Headers (Patient-Headers 5501)
#>

# 密钥来自环境变量，必须和服务端一致。
# 服务端启动时如果没设这个变量，它会**随机生成**一个（启动日志有红字警告），
# 那样脚本签出来的令牌永远对不上 —— 所以先设变量，再启动服务端，再跑脚本。
function Get-PatientSecret {
    $s = $env:PATIENT_TOKEN_SECRET
    if ([string]::IsNullOrWhiteSpace($s)) {
        throw @"
缺少环境变量 PATIENT_TOKEN_SECRET。
抢号接口的身份来自签名令牌，脚本要用同一个密钥自己签，两边必须一致：
    `$env:PATIENT_TOKEN_SECRET = "bench-secret"     # 先设，再启动服务端
    `$env:PATIENT_TOKEN_SECRET = "bench-secret"     # 再跑这个脚本
为什么不给默认值：那等于所有部署共享同一个签名密钥。
"@
    }
    return $s
}

<#
.SYNOPSIS
    签一个患者令牌：<patientId>.<base64url(HMAC-SHA256(secret, patientId))>
#>
function New-PatientToken {
    param(
        [Parameter(Mandatory = $true)][long] $PatientId,
        [string] $Secret = $null
    )
    if ([string]::IsNullOrWhiteSpace($Secret)) { $Secret = Get-PatientSecret }

    $mac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $raw = $mac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes([string]$PatientId))
    } finally {
        $mac.Dispose()
    }
    # base64url：+ → -、/ → _、去掉 = 填充。
    # 不转的话令牌放进查询串会被转义，放进请求头也容易出问题。
    $b64 = [Convert]::ToBase64String($raw).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    return "$PatientId.$b64"
}

<#
.SYNOPSIS
    构造带患者身份的请求头。
#>
function Patient-Headers {
    param(
        [Parameter(Mandatory = $true)][long] $PatientId,
        [string] $Secret = $null
    )
    return @{ "X-Patient-Token" = (New-PatientToken -PatientId $PatientId -Secret $Secret) }
}

<#
.SYNOPSIS
    运维接口（/admin、/verify、/control、/mcp）的请求头。

.DESCRIPTION
    这些接口由 AdminGuard 守着，规则是「来自本机 || 令牌正确」。
    脚本默认跑在本机，所以通常不需要令牌，返回空表即可 ——
    设了 ADMIN_TOKEN 就带上，用于打远程实例。
#>
function Admin-Headers {
    if ([string]::IsNullOrWhiteSpace($env:ADMIN_TOKEN)) { return @{} }
    return @{ "X-Admin-Token" = $env:ADMIN_TOKEN }
}
