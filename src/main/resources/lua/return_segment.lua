-- 优雅归还：实例正常下线时把本地没卖完的号段还回桶。
-- 这一步能覆盖绝大多数「正常发布」场景，宕机场景才需要靠 reclaim_leases.lua 兜底。
--
-- 还回哪个桶？还给当前余量最少的桶 —— 顺手做一次再平衡，降低桶倾斜度。
--
-- KEYS[1..n]  n 个桶
-- KEYS[n+1]   租约 hash
-- ARGV[1]     n
-- ARGV[2]     instanceId
-- ARGV[3]     amount（想还的数量）
--
-- 返回实际归还数量。会以租约记录的持有量为上限，防止重复归还造成超卖。

-- ARGV[1]  物理桶数（决定 KEYS 布局：租约表在 KEYS[n+1]）
-- ARGV[2]  实例 ID
-- ARGV[3]  想归还的数量
-- ARGV[4]  活跃桶数。**归还只落在这个范围内**，理由见 reclaim_leases.lua 的头部注释
local n = tonumber(ARGV[1])
local leaseKey = KEYS[n + 1]
local inst = ARGV[2]
local want = tonumber(ARGV[3])
local active = tonumber(ARGV[4]) or n
if active < 1 then active = 1 end
if active > n then active = n end
local heldField = 'h:' .. inst

local held = tonumber(redis.call('HGET', leaseKey, heldField) or '0')
if held <= 0 or want <= 0 then
    redis.call('HDEL', leaseKey, heldField, 'e:' .. inst)
    return 0
end

local give = want
if held < give then give = held end

-- 找余量最少的桶
-- 只在活跃桶里挑最空的那个
local target, minVal = 1, nil
for i = 1, active do
    local v = tonumber(redis.call('GET', KEYS[i]) or '0')
    if minVal == nil or v < minVal then
        target, minVal = i, v
    end
end

redis.call('INCRBY', KEYS[target], give)
local left = redis.call('HINCRBY', leaseKey, heldField, -give)
if tonumber(left) <= 0 then
    redis.call('HDEL', leaseKey, heldField, 'e:' .. inst)
end

return give
