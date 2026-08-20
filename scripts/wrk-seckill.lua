-- wrk 压测脚本（给以后在 WSL / Linux 上用；Windows 下直接用内置的 LoadGenerator 更省事）
--
--   # 1. 先生成令牌池（wrk 的 Lua 里没有 HMAC-SHA256）
--   pwsh ./scripts/gen-tokens.ps1 -Count 200000 -OutFile bench-tokens.txt
--   # 2. 用同一个密钥启动服务端，然后：
--   wrk -t8 -c400 -d30s -s scripts/wrk-seckill.lua http://127.0.0.1:8090
--
-- 注意 wrk 只能看 HTTP 状态码，而本项目「售罄」「限流」都返回 200 + 业务码，
-- 所以业务维度的分类统计要靠脚本自己解析响应体（下面的 response 钩子）。
--
-- ─────────────────────────────────────────────────────────────
-- 这个脚本原来是坏的，而坏法很有代表性，值得留个记录：
--
--   路径拼的是 "?userId=" ，而接口收的参数叫 holderId。
--   于是每一发都是 400 Bad Request，全部落进 counter.other ——
--   **压测能跑完、能出延迟数字、能打印报告**，只是那些数字描述的是
--   「400 有多快」。它一直没被发现，因为它从来没在 Linux 上真跑过。
--
-- 教训：压测脚本必须有一个「第一发不是预期结果就退出」的自检，
-- 否则它失败的方式是给你一份好看的假数据。内置的 LoadGenerator 现在有了
-- （见 preflight），这里也照做。
-- ─────────────────────────────────────────────────────────────

local item_id = 1001

-- 令牌池。身份改成了 HMAC 签名令牌（X-Patient-Token），不再是 ?holderId= ——
-- 因为客户端能自己报身份的话，风控那套按患者 ID 计数的频次判据
-- 只要每次换个随机值就整套失效。
local token_file = os.getenv("FP_TOKEN_FILE") or "bench-tokens.txt"
local tokens = {}

do
    local f = io.open(token_file, "r")
    if not f then
        error("
找不到令牌池文件 " .. token_file ..
              "
先生成它：pwsh ./scripts/gen-tokens.ps1 -Count 200000" ..
              "
（服务端必须用同一个 PATIENT_TOKEN_SECRET 启动）")
    end
    for line in f:lines() do
        if #line > 0 then tokens[#tokens + 1] = line end
    end
    f:close()
    if #tokens == 0 then
        error("令牌池文件 " .. token_file .. " 是空的")
    end
end

local counter = {
    success = 0,
    sold_out = 0,
    duplicate = 0,
    rate_limited = 0,
    other = 0,
}

request = function()
    -- 从池子里随机取一个令牌。取随机而不是顺序轮转，是为了不让同一个
    -- 患者 ID 在各线程间形成规律 —— 那会让风控的频次判据看到人造的模式。
    local token = tokens[math.random(1, #tokens)]
    return wrk.format("POST", "/seckill/" .. item_id, { ["X-Patient-Token"] = token })
end

-- 自检：第一发响应不对就直接停，别让压测跑完给出一份好看的假数据。
-- 这正是这个脚本以前的失败方式（见文件开头）。
local checked = false

response = function(status, headers, body)
    if not checked then
        checked = true
        if status == 401 then
            error("
服务端拒绝了令牌池里的令牌（401）。" ..
                  "
两边的 PATIENT_TOKEN_SECRET 不一致 —— 注意服务端没设这个变量时" ..
                  "
会**随机生成**一个密钥（启动日志有红字），那样永远对不上。")
        elseif status ~= 200 then
            error("
自检失败：第一发返回 HTTP " .. status .. "
响应体：" .. tostring(body))
        end
    end
    if status ~= 200 then
        counter.other = counter.other + 1
    elseif string.find(body, '"code":200', 1, true) then
        counter.success = counter.success + 1
    elseif string.find(body, '"code":4001', 1, true) then
        counter.sold_out = counter.sold_out + 1
    elseif string.find(body, '"code":4002', 1, true) then
        counter.duplicate = counter.duplicate + 1
    elseif string.find(body, '"code":4290', 1, true) then
        counter.rate_limited = counter.rate_limited + 1
    else
        counter.other = counter.other + 1
    end
end

done = function(summary, latency, requests)
    io.write("\n--------- 业务维度统计 ---------\n")
    io.write(string.format("  成交        %d\n", counter.success))
    io.write(string.format("  售罄        %d\n", counter.sold_out))
    io.write(string.format("  重复购买    %d\n", counter.duplicate))
    io.write(string.format("  限流拒绝    %d\n", counter.rate_limited))
    io.write(string.format("  其它/异常   %d\n", counter.other))
    io.write("--------- 延迟 ---------\n")
    for _, p in pairs({ 50, 95, 99, 99.9 }) do
        io.write(string.format("  P%-5s %8.2f ms\n", p, latency:percentile(p) / 1000))
    end
    io.write("\n下一步：GET /verify/check 做一致性校验\n")
end
