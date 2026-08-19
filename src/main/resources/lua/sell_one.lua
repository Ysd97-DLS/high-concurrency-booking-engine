-- 号段模式下每卖出一件时执行：发出成交事件 + 从租约持有量里扣 1。
-- 这两件事必须原子，否则实例挂在中间会导致「事件发了但租约没减」→ 回收时多还 → 超卖。
--
-- 调用顺序很关键（见 README「为什么先扣本地再发事件」）：
--   ① 本地 AtomicLong 先扣（纯内存，快）
--   ② 本脚本：XADD + HINCRBY -1
--   ③ 才给用户返回成功
-- 如果实例在 ① 之后 ② 之前挂掉：内存里的预占随进程消失，Redis 里租约仍记着这一件，
-- 回收任务会把它还回桶 —— 不丢不多，正好。
--
-- KEYS[1]  stream key
-- KEYS[2]  租约 hash key
-- ARGV[1]  instanceId
-- ARGV[2]  holderId
-- ARGV[3]  poolId
-- ARGV[4]  时间戳（毫秒，由 Java 传入，脚本里不取时间以保证可复制）
--
-- 返回 {code, streamIdOrEmpty}
--   code 1 正常；0 表示租约持有量已 <= 0（Redis 与本地状态不一致，属于 bug，宁可拒绝也不超卖）

local leaseKey = KEYS[2]
local inst = ARGV[1]
local heldField = 'h:' .. inst

local held = tonumber(redis.call('HGET', leaseKey, heldField) or '0')
if held <= 0 then
    return { 0, '' }
end

redis.call('HINCRBY', leaseKey, heldField, -1)

local id = redis.call('XADD', KEYS[1], '*',
        'holderId', ARGV[2],
        'poolId', ARGV[3],
        'instanceId', inst,
        'ts', ARGV[4],
        'mode', 'SEGMENT')

return { 1, id }
