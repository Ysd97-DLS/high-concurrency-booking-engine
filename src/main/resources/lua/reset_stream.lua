-- 把 stream 清空，但**保留 stream 本身和消费组**。仅用于实验重置。
--
-- 为什么不能直接 DEL：消费者容器正在轮询这个 key，删掉的瞬间它会收到 NOGROUP 错误，
-- 而 Spring 的 StreamMessageListenerContainer 在某些错误下会直接取消订阅 ——
-- 结果就是「消费者悄悄停了」，你还以为它在跑，下一轮实验的数据全是错的。
-- 这种「工具本身出问题导致实验数据错误」的坑最难查，宁可多写几行 Lua。
--
-- XTRIM MAXLEN 0 清掉全部消息但不动 key 和消费组，XLEN 归零，
-- 而后续 XADD 生成的 ID 一定大于消费组的 last-delivered-id，所以新消息照常投递。
--
-- 顺便把残留的 PEL（上一轮没 ACK 完的）全部 ACK 掉，
-- 否则等式④ 会一直挂着上一轮的未处理数。
--
-- KEYS[1]  stream key
-- ARGV[1]  消费组

redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
redis.call('XTRIM', KEYS[1], 'MAXLEN', 0)

local pending = redis.call('XPENDING', KEYS[1], ARGV[1], '-', '+', 1000)
local acked = 0
for i = 1, #pending do
    redis.call('XACK', KEYS[1], ARGV[1], pending[i][1])
    acked = acked + 1
end

return acked
