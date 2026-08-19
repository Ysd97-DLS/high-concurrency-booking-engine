-- 幂等地创建消费组。
--
-- 为什么要用脚本而不是直接调 API：需要 MKSTREAM 选项（stream 不存在时顺便建一个空的），
-- 又不能靠「先 XADD 一条假消息把 stream 建出来」——那会污染 XLEN，
-- 而一致性校验的等式 ③④ 都依赖 XLEN 的准确性。
--
-- redis.pcall 遇到 BUSYGROUP（组已存在）不会中断脚本，正好实现幂等。
--
-- KEYS[1]  stream key
-- ARGV[1]  消费组名

redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
return 1
