-- 回收过期租约：把宕机实例手里攥着的未售库存还回桶。
-- 这是「少卖」问题的最后一道防线 —— 也是这个项目里我认为最值钱的一段逻辑，
-- 因为全网的秒杀教程都在防超卖，几乎没人处理少卖。
--
-- 幂等性：校验 + 加回桶 + 删除租约字段全在一个脚本里完成，
-- 所以多个实例的巡检任务同时跑也不会重复归还。
--
-- KEYS[1..n]  n 个桶
-- KEYS[n+1]   租约 hash
-- ARGV[1]     n
-- ARGV[2]     nowMs（由 Java 传入，脚本内不取时间）
--
-- 返回 {reclaimedTotal, instanceCount}

-- ARGV[1]  物理桶数（决定 KEYS 布局：租约表在 KEYS[n+1]）
-- ARGV[2]  当前时间毫秒
-- ARGV[3]  活跃桶数。**归还只落在这个范围内**
--
-- 为什么要两个桶数：原来只有一个 n，同时充当「KEYS 下标」和「扫描范围」两个职责，
-- 于是它被钉死在 MAX_BUCKETS（否则 KEYS[n+1] 就不是租约表了），
-- 归还也就只能挑全部 32 个桶里最空的那个 —— preheat 之后非活跃桶全是 0，
-- 每次回收都必然落进非活跃桶。实测：8 活跃桶、回收 17 个号，17 全进了物理桶 8。
--
-- 落到非活跃桶的号只有借调路径能碰到，直连请求（prefIndex = holderId % activeBuckets）
-- 永远命不中 —— 这正是第 5 号 bug 的形态。当时只修了 release_slots.lua，
-- 因为那个脚本没有租约键、n 没有被键布局绑住，改起来没有阻力；
-- 而这两条真正兜少卖的路径反倒漏掉了。
-- <b>教训：一个参数承担两个职责，会让修复止步于阻力最小的那一处。</b>
local n = tonumber(ARGV[1])
local leaseKey = KEYS[n + 1]
local now = tonumber(ARGV[2])
local active = tonumber(ARGV[3]) or n
if active < 1 then active = 1 end
if active > n then active = n end

local all = redis.call('HGETALL', leaseKey)
local reclaimed = 0
local instances = 0

for i = 1, #all, 2 do
    local field = all[i]
    local value = all[i + 1]
    -- 只看到期时间字段
    if string.sub(field, 1, 2) == 'e:' then
        local expireAt = tonumber(value) or 0
        if expireAt < now then
            local inst = string.sub(field, 3)
            local heldField = 'h:' .. inst
            local held = tonumber(redis.call('HGET', leaseKey, heldField) or '0')
            if held > 0 then
                -- 还给余量最少的桶，顺手再平衡
                -- 只在活跃桶里挑最空的那个
                local target, minVal = 1, nil
                for j = 1, active do
                    local v = tonumber(redis.call('GET', KEYS[j]) or '0')
                    if minVal == nil or v < minVal then
                        target, minVal = j, v
                    end
                end
                redis.call('INCRBY', KEYS[target], held)
                reclaimed = reclaimed + held
            end
            redis.call('HDEL', leaseKey, heldField, field)
            instances = instances + 1
        end
    end
end

return { reclaimed, instances }
