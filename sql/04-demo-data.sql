-- ---------------------------------------------------------------------------
-- 演示数据：让患者端一打开就有号可挂。
--
-- 为什么需要单独一份而不是写在 02-clinic-schema.sql 里：
-- 02 的种子排班日期是写死的（'2026-08-18'），过了那天患者端就永远空着，
-- 因为 /clinic/schedules 默认查「明天」。演示数据必须相对 CURDATE() 生成。
-- 这份脚本可以<b>反复执行</b>，每次把未来 7 天的排班刷新到今天为基准。
--
-- 另一个必须分开的原因：这里会删数据（清掉测试期间攒下的临时排班），
-- 而 schema 脚本必须是纯建表 + 幂等种子，不能带删除动作。
--
-- 用法：
--   docker exec -e MYSQL_PWD=flashpilot fp-mysql \
--     mysql -uroot flashpilot < sql/04-demo-data.sql
--   然后调 POST /admin/schedules/{id}/open 把号推进 Redis（见文件末尾说明）
-- ---------------------------------------------------------------------------

-- ① 修复不可能状态：放号进度不能超过总号数。
--
-- 测试期间出现过 released_slots=150 / total_slots=50 的排班，根因是
-- resetForExperiment() 改了 total_slots 却没清 released_slots（已在代码里修掉）。
-- 后果是这个排班的放号进度显示 300%，且 ReleaseService.open() 因为
-- released >= total 永久拒绝放号 —— 只能改库修复，所以这里补一条。
UPDATE t_schedule SET released_slots = LEAST(released_slots, total_slots)
 WHERE released_slots > total_slots;

-- ② 把压测号池挪出患者可见范围。
--
-- 1001 / 9999 是压测用的十万号大池子（poolId 1001 和历史 itemId 对齐，别改 id）。
-- 它们本来落在「明天」，于是患者端第一屏就出现「李建国 余 100000/100000」，
-- 一眼假。挪到 2099 年既保留压测入口（压测按 poolId 直连，跟日期无关），
-- 又不会出现在患者端的日期筛选里。
UPDATE t_schedule SET visit_date = '2099-01-01', status = 'OPEN'
 WHERE id IN (1001, 9999);

-- ③ 清掉测试期间临时建的排班（保留压测池）。
--
-- 这些是端到端验证接口时用 POST /admin/schedules 建的，号别/号量都是随手填的，
-- 留着会让患者端和看板都很乱。同时删掉挂在它们上面的预约，避免孤儿数据。
DELETE FROM t_appointment WHERE schedule_id NOT IN (1001, 9999);
DELETE FROM t_schedule WHERE id NOT IN (1001, 9999);

-- ④ 生成未来 7 天的真实感排班。
--
-- 5 位医生 × 7 天。号量刻意按职称分层，这是挂号场景的真实分布，
-- 也是「为什么专家号才是秒杀场景」的直接体现：
--   主任医师 50 个 —— 抢的就是这个
--   副主任   80 个
--   主治    120 个 —— 基本不需要抢
--
-- id 用 20000 + 天偏移 × 5 + 医生序号，是个确定式编号：
-- 脚本重跑时靠主键命中 ON DUPLICATE KEY 原地更新日期，不会越跑越多。
--
-- status 刻意建成 PENDING、released_slots = 0：
-- **号必须走 ReleaseService.open() 才能进 Redis 桶。**
-- 直接在 SQL 里把 released_slots 写成 total_slots 是错的 ——
-- MySQL 说号放完了而 Redis 桶里空的，患者会全部收到「号源已满」，
-- 而看板显示 100% 放号完成。这正是那种「看起来成功」的假象。
-- 注意这里必须把 SELECT 整个包一层 `SELECT * FROM ( ... ) AS src`。
-- 直接写 `... CROSS JOIN (...) doc ON DUPLICATE KEY UPDATE` 会报 1064：
-- 解析器看到 `CROSS JOIN ... ON` 就把 `DUPLICATE KEY UPDATE` 当成 JOIN 的连接条件了。
-- 包一层之后 JOIN 的作用域在子查询里闭合，ON DUPLICATE KEY 才能被正确识别。
--
-- 顺带的好处：UPDATE 子句可以引用 src.xxx，不用 VALUES()（MySQL 8.0.20 起已废弃）。
INSERT INTO t_schedule (id, hospital_id, doctor_id, department_id, visit_date, period,
                        slot_type, fee_cents, total_slots, booked_slots, released_slots,
                        release_at, visit_start, visit_end, status)
SELECT * FROM (
    SELECT 20000 + d.n * 5 + doc.idx      AS id,
           1                              AS hospital_id,
           doc.doctor_id                  AS doctor_id,
           doc.department_id              AS department_id,
           DATE_ADD(CURDATE(), INTERVAL d.n DAY) AS visit_date,
           doc.period                     AS period,
           doc.slot_type                  AS slot_type,
           doc.fee_cents                  AS fee_cents,
           doc.total_slots                AS total_slots,
           0                              AS booked_slots,
           0                              AS released_slots,
           -- 放号时刻：就诊日前 7 天早上 7 点。真实医院的洪峰就在这一秒。
           TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.n - 7 DAY), '07:00:00') AS release_at,
           doc.visit_start                AS visit_start,
           doc.visit_end                  AS visit_end,
           'PENDING'                      AS status
      FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
            UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) d
     CROSS JOIN (
            SELECT 1 AS idx, 101 AS doctor_id, 1 AS department_id, 'AM' AS period,
                   'EXPERT' AS slot_type, 5000 AS fee_cents, 50 AS total_slots,
                   '08:00:00' AS visit_start, '11:30:00' AS visit_end
            UNION ALL SELECT 2, 102, 1, 'AM', 'EXPERT', 3000,  80, '08:00:00', '11:30:00'
            UNION ALL SELECT 3, 103, 1, 'PM', 'NORMAL', 1500, 120, '13:30:00', '17:00:00'
            UNION ALL SELECT 4, 201, 2, 'AM', 'EXPERT', 5000,  40, '08:00:00', '11:30:00'
            UNION ALL SELECT 5, 301, 3, 'AM', 'NORMAL', 1500, 100, '08:00:00', '11:30:00'
     ) doc
) AS src
ON DUPLICATE KEY UPDATE
   visit_date     = src.visit_date,
   release_at     = src.release_at,
   total_slots    = src.total_slots,
   booked_slots   = 0,
   released_slots = 0,
   status         = 'PENDING';

-- ⑤ 患者：清掉失约累计和拉黑时间，否则上一轮风控实验拉黑的患者还是约不上号。
-- 拉黑用的是 blocked_until（非空且未过期则禁止预约），不是布尔列 —— 因为限制是
-- 「30 天」而不是「永久」，存到期时刻才能自动解除，不需要定时任务去翻。
UPDATE t_patient SET no_show_count = 0, blocked_until = NULL;

SELECT '演示排班已就绪' AS step,
       COUNT(*) AS schedules,
       MIN(visit_date) AS from_date,
       MAX(visit_date) AS to_date
  FROM t_schedule WHERE id >= 20000;

-- ---------------------------------------------------------------------------
-- 最后一步在应用侧，不在 SQL 里：
--
--   for id in 20001..20035: POST /admin/schedules/{id}/open
--
-- 这一步才把号推进 Redis 桶。**刻意不在 SQL 里模拟它**：
-- 放号是「先在 MySQL 预留进度、再推进 Redis」的两步操作（见 ReleaseService.releaseBatch），
-- 只写 SQL 就只完成了前一半，两个存储会不一致。
-- 让它走真实代码路径，顺便也验证了放号链路本身。
--
-- scripts/seed-demo.ps1 把这两步串起来了，直接跑那个即可。
-- ---------------------------------------------------------------------------
