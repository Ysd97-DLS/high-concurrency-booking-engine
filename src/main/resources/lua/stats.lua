-- 一次性读出库存的全局状态，供一致性校验器和指标采集使用。
-- 单独做成脚本是为了拿到「同一时刻」的快照 —— 分成多次 GET 会读到撕裂的中间态，
-- 而库存守恒等式对撕裂非常敏感（会误报少卖）。
--
-- KEYS[1..n]  n 个物理桶
-- KEYS[n+1]   租约 hash
-- ARGV[1]     n
--
-- 返回扁平数组 {bucketSum, leaseHeld, instanceCount, b1, b2, ..., bn}
--   前三个是汇总值，后面是每个桶的余量（Java 侧用活跃桶那一段算桶倾斜度）

local n = tonumber(ARGV[1])
local leaseKey = KEYS[n + 1]

local out = { 0, 0, 0 }
local sum = 0

for i = 1, n do
    local v = tonumber(redis.call('GET', KEYS[i]) or '0')
    sum = sum + v
    out[3 + i] = v
end

local all = redis.call('HGETALL', leaseKey)
local held, instances = 0, 0
for i = 1, #all, 2 do
    if string.sub(all[i], 1, 2) == 'h:' then
        held = held + (tonumber(all[i + 1]) or 0)
        instances = instances + 1
    end
end

out[1] = sum
out[2] = held
out[3] = instances
return out
