-- 领取一个号段：先扣首选桶，不够则从余量最多的桶「借调」补齐。
-- 这是整个数据面的核心脚本，也是面试最容易被深挖的地方。
--
-- KEYS[1..n]   n 个桶的 key
-- KEYS[n+1]    租约 hash 的 key
-- ARGV[1]      need        本次想领的数量（号段大小）
-- ARGV[2]      prefIdx     首选桶下标（0-based，由 holderId 或实例 id 哈希得到）
-- ARGV[3]      instanceId  领取者
-- ARGV[4]      expireAtMs  本次续约后的租约到期时间
-- ARGV[5]      n           桶数量
--
-- 返回 {got, stolen, remaining}
--   got        实际领到的数量（可能小于 need，甚至为 0）
--   stolen     是否发生过借调（1/0），用于埋点算借调触发率
--   remaining  领完之后所有桶的剩余总量，Java 侧用它判断要不要进尾部模式

local n = tonumber(ARGV[5])
local need = tonumber(ARGV[1])
local pref = tonumber(ARGV[2]) + 1
local inst = ARGV[3]
local expireAt = ARGV[4]
local leaseKey = KEYS[n + 1]

if pref < 1 or pref > n then pref = 1 end

local got = 0
local stolen = 0

-- 1) 先扣首选桶。绝大多数请求在这里就结束了，下面的循环根本不会进。
local cur = tonumber(redis.call('GET', KEYS[pref]) or '0')
if cur > 0 then
    local take = need
    if cur < need then take = cur end
    redis.call('DECRBY', KEYS[pref], take)
    got = got + take
end

-- 2) 借调：从余量最多的桶补齐。n 被钳制在个位数到十几，O(n) 遍历可接受。
while got < need do
    local best, bestVal = 0, 0
    for i = 1, n do
        local v = tonumber(redis.call('GET', KEYS[i]) or '0')
        if v > bestVal then
            best, bestVal = i, v
        end
    end
    -- 所有桶都空了，才是真的全局售罄
    if bestVal <= 0 then break end
    local take = need - got
    if bestVal < take then take = bestVal end
    redis.call('DECRBY', KEYS[best], take)
    got = got + take
    stolen = 1
end

-- 3) 记账到租约表。h: 持有量，e: 到期时间。
--    这一步必须和扣减在同一个脚本里，否则实例在两步之间挂掉就会凭空少库存。
if got > 0 then
    redis.call('HINCRBY', leaseKey, 'h:' .. inst, got)
end
redis.call('HSET', leaseKey, 'e:' .. inst, expireAt)

-- 4) 算剩余总量，供 Java 判断是否进入尾部模式
local remaining = 0
for i = 1, n do
    remaining = remaining + tonumber(redis.call('GET', KEYS[i]) or '0')
end

return { got, stolen, remaining }
