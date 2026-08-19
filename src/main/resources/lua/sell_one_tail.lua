-- 尾部模式：不走号段，直接从桶里扣 1 件并发出事件。
--
-- 为什么需要这个模式：号段会把库存切碎分散到各实例手里，活动尾声可能出现
-- 「全局还剩 3 件，但分别卡在 3 个实例的本地余量里，而用户都打到了第 4 个实例」。
-- 所以全局剩余低于阈值时关闭号段，退化为单件直扣 —— 慢一点，但保证卖得干净。
--
-- KEYS[1..n]  n 个桶
-- KEYS[n+1]   stream key
-- ARGV[1]     n（桶数）
-- ARGV[2]     prefIdx（首选桶下标，0-based）
-- ARGV[3]     holderId
-- ARGV[4]     poolId
-- ARGV[5]     时间戳（毫秒）
--
-- 返回 {code, streamIdOrEmpty, remaining}
--   code 1 成功；0 全局售罄
--   remaining 只在售罄（code=0）时才是真实值；成功时返回 -1 表示「没算」。
--             这是刻意的性能优化：尾部模式是每请求打一次 Redis 的路径，
--             而调用方只在售罄时才需要 remaining，没必要每次都多扫 n 次 GET。

local n = tonumber(ARGV[1])
local pref = tonumber(ARGV[2]) + 1
local streamKey = KEYS[n + 1]

if pref < 1 or pref > n then pref = 1 end

local hit = 0

-- 先试首选桶
if tonumber(redis.call('GET', KEYS[pref]) or '0') > 0 then
    redis.call('DECRBY', KEYS[pref], 1)
    hit = pref
else
    -- 借调：找任意一个还有货的桶，找到就走，不必找最富的那个
    for i = 1, n do
        if tonumber(redis.call('GET', KEYS[i]) or '0') > 0 then
            redis.call('DECRBY', KEYS[i], 1)
            hit = i
            break
        end
    end
end

if hit == 0 then
    -- 只有在真的没扣到的时候才去统计剩余量，用来让调用方确认「全局售罄」
    local remaining = 0
    for i = 1, n do
        remaining = remaining + tonumber(redis.call('GET', KEYS[i]) or '0')
    end
    return { 0, '', remaining }
end

local id = redis.call('XADD', streamKey, '*',
        'holderId', ARGV[3],
        'poolId', ARGV[4],
        'instanceId', 'tail',
        'ts', ARGV[5],
        'mode', 'TAIL')

return { 1, id, -1 }
