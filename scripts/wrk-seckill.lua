-- wrk 压测脚本（给以后在 WSL / Linux 上用；Windows 下直接用内置的 LoadGenerator 更省事）
--
--   wrk -t8 -c400 -d30s -s scripts/wrk-seckill.lua http://127.0.0.1:8080
--
-- 注意 wrk 只能看 HTTP 状态码，而本项目「售罄」「限流」都返回 200 + 业务码，
-- 所以业务维度的分类统计要靠脚本自己解析响应体（下面的 response 钩子）。

local item_id = 1001
local user_space = 200000

local counter = {
    success = 0,
    sold_out = 0,
    duplicate = 0,
    rate_limited = 0,
    other = 0,
}

request = function()
    local user_id = math.random(1, user_space)
    local path = "/seckill/" .. item_id .. "?userId=" .. user_id
    return wrk.format("POST", path)
end

response = function(status, headers, body)
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
