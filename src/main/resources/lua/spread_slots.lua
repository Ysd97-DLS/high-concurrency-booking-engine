-- 把一批号源**均摊**到活跃桶，而不是全堆进一个桶。
--
-- 为什么需要它，而 release_slots.lua 不够：
--   release_slots 的策略是「挑当前余量最少的活跃桶，把 amount 全加进去」。
--   那个策略对<b>单个号的归还</b>（退号、超时释放）是最优的 —— 每次一个，
--   自然就会流向最空的桶，顺带削平倾斜。
--   但<b>放号</b>走的是同一个函数，而放号一次是几十到几万个：
--   于是整批号全进一个桶。实测排班 20006 放 50 个号之后桶分布是
--   `45 / 5 / 0 / 0 / 0 / 0 / 0 / 0`，桶倾斜度 8.000。
--
-- 后果不是抢不到号（号段 + 借调把功能兜住了，实测 16 次抢号全成功、只借调 1 次），
-- 而是<b>桶分片的意图被架空</b>：
--   · 直连命中只剩 1/activeBuckets（prefIndex = holderId % activeBuckets）；
--   · 其余请求全靠借调，而借调要扫 32 个物理桶；
--   · 号池一大，那个独苗桶就是所有借调的写热点 —— 分桶本来就是为了消除这种热点。
--
-- 算法是「填平」而不是「平均分」：目标值按 (现有总量 + amount) / n 算，
-- 然后优先补给低于目标的桶。这样无论初始分布如何，放完都尽量均等；
-- 而单纯 amount/n 平均分会把已有的倾斜原封不动保留下来。
--
-- 只落在**活跃桶**范围内，理由同 release_slots：落到非活跃桶的号，
-- 直连请求永远命不中，只有借调偶尔碰到，等于半失联。
--
-- KEYS[1..32]  全部物理桶（沿用全局约定）
-- ARGV[1]      活跃桶数
-- ARGV[2]      amount（要放的总量）
--
-- 返回 { 实际放入总量, 参与分配的桶数 }

local n = tonumber(ARGV[1])
local amount = tonumber(ARGV[2])

if n < 1 or amount < 1 then
    return { 0, 0 }
end

-- 读活跃桶现状
local cur = {}
local total = 0
for i = 1, n do
    local v = tonumber(redis.call('GET', KEYS[i]) or '0')
    cur[i] = v
    total = total + v
end

-- 填平的目标线
local goal = math.floor((total + amount) / n)

-- 第一轮：把低于目标线的桶补到目标线（补不完就按剩余量截断）
local left = amount
local touched = 0
for i = 1, n do
    if left <= 0 then
        break
    end
    local need = goal - cur[i]
    if need > 0 then
        local give = need
        if give > left then
            give = left
        end
        redis.call('INCRBY', KEYS[i], give)
        cur[i] = cur[i] + give
        left = left - give
        touched = touched + 1
    end
end

-- 第二轮：整除的余数（最多 n-1 个）逐桶各放一个，从最空的开始
-- 用循环挑最小值而不是排序：n 最多 32，O(n²) 也就一千次比较，
-- 而 Lua 里排序要建表和比较函数，反而更啰嗦。
while left > 0 do
    local target = 1
    local minv = cur[1]
    for i = 2, n do
        if cur[i] < minv then
            minv = cur[i]
            target = i
        end
    end
    redis.call('INCRBY', KEYS[target], 1)
    cur[target] = cur[target] + 1
    left = left - 1
    if touched < n then
        touched = touched + 1
    end
end

return { amount, touched }
