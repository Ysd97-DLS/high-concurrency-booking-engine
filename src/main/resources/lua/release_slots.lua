-- 把号源还回号池。用于「预约单不再占号」的三条路径中的两条：
--   PENDING_PAY → EXPIRED   超时未支付
--   BOOKED      → REFUNDED  患者退号
-- （NO_SHOW 刻意不归还，理由见 docs/DOMAIN.md §3.2）
--
-- 和 return_segment.lua 的区别很重要：
--   return_segment 是「实例把没卖完的本地号段还回去」，要同时扣减该实例的租约持有量；
--   这里是「已经卖出去的号又被取消了」，号源在引擎账上早已从实例转移出去，
--   <b>没有任何租约需要扣</b>，纯粹是把数量加回桶。
-- 如果误用 return_segment，会把某个实例的租约扣成负数，等式③ 立刻不平。
--
-- 还回哪个桶：在<b>活跃桶</b>范围内选当前余量最少的那个。
-- 这样能顺带削平桶倾斜，而不是无脑加到 0 号桶造成新的热点。
--
-- 注意 ARGV[1] 传的是「活跃桶数」而不是物理桶数 32，这和 take_segment 刻意不同：
-- 取号时借调会扫全部 32 个物理桶，所以任何桶里的号都捞得到；
-- 但还号如果落到非活跃桶，直连请求（prefIndex = holderId % activeBuckets）永远命不中，
-- 只有借调路径偶尔碰到，号源等于半失联。
--
-- KEYS[1..32]  全部物理桶（沿用全局约定）
-- ARGV[1]      活跃桶数，归还只落在这个范围内
-- ARGV[2]      amount（归还数量）
--
-- 返回 { 实际归还量, 归还到的桶下标(0-based) }

local n = tonumber(ARGV[1])
local amount = tonumber(ARGV[2])

if n < 1 or amount < 1 then
    return { 0, -1 }
end

local target = 1
local minv = nil
for i = 1, n do
    local v = tonumber(redis.call('GET', KEYS[i]) or '0')
    if minv == nil or v < minv then
        minv = v
        target = i
    end
end

redis.call('INCRBY', KEYS[target], amount)
return { amount, target - 1 }
