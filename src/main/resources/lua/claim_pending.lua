-- 抢过来那些「已投递但长时间没 ACK」的消息，交给当前消费者重试。
-- 典型场景：某个消费者实例宕机，它已读取但没处理完的消息会永远躺在 pending 列表里，
-- 必须有人把它们捞回来，否则用户「抢到了但没订单」。
--
-- 直接用 XAUTOCLAIM 而不是 XPENDING + XCLAIM 两步，是因为 XAUTOCLAIM
-- 不需要先知道原持有者是谁 —— 而宕机实例的消费者名字我们恰恰不知道。
--
-- 返回值被刻意拍平成 {id1, userId1, itemId1, id2, userId2, itemId2, ...}，
-- 因为 XAUTOCLAIM 原始返回是三层嵌套数组，在 Java 侧解析很难看。
--
-- KEYS[1]  stream key
-- ARGV[1]  消费组
-- ARGV[2]  新的持有者（当前消费者名）
-- ARGV[3]  最小空闲毫秒数，只抢闲置超过这个时间的
-- ARGV[4]  一次最多抢几条

-- ARGV[5] 起始游标。**必须由调用方传入并逐轮推进。**
--
-- 原来这里硬写 '0-0'，每轮都从 PEL 头部重新扫。抢到的消息 idle 会被重置成 0，
-- 于是下一轮不再够格 —— 但仍然要被<b>扫过</b>。PEL 有 6 万条时，第 20 轮要空扫
-- 前面 3.8 万条才能找到够格的。
--
-- 更糟的是 Redis 对单次 XAUTOCLAIM 的扫描量有内部上限，扫描被截断时会提前返回、
-- 并用返回值里的游标告诉你"下次从这里继续"。而调用方拿不到游标，只看到
-- 「这一批不足 COUNT」→ 判定 PEL 已排空 → **提前 break**。
-- 结果是每次调度都在重复空扫，而 PEL 始终排不干净 —— 一个看起来在工作的自愈机制。
--
-- 返回值第一项固定是下一轮的游标，其后每三项为 (id, holderId, poolId)。
local res = redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[5], 'COUNT', ARGV[4])
local out = { res[1] }
local entries = res[2]
if entries == nil then
    return out
end
-- res[3]（Redis 7+）是"消息已从 stream 删除"的 id 列表，XAUTOCLAIM 已自动把它们
-- 从 PEL 摘掉，这里无需处理；entry[2] 为 nil 的就是这类。
for i = 1, #entries do
    local entry = entries[i]
    if entry ~= nil and entry[2] ~= nil then
        local id = entry[1]
        local fields = entry[2]
        local holderId, poolId = '', ''
        for j = 1, #fields, 2 do
            if fields[j] == 'holderId' then holderId = fields[j + 1] end
            if fields[j] == 'poolId' then poolId = fields[j + 1] end
        end
        table.insert(out, id)
        table.insert(out, holderId)
        table.insert(out, poolId)
    end
end
return out
