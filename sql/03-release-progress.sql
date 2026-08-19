-- 分批放号需要记录「已经放出多少号」。
--
-- 为什么不能只靠 Redis 桶里的余量推算：桶里的数字会被抢号扣减、被退号加回，
-- 它反映的是「现在还剩多少」，而放号进度要的是「累计放出了多少」。
-- 两者在有成交之后就不再相等，混用会导致分批放号重复灌号 —— 那就是超卖。

USE flashpilot;

ALTER TABLE t_schedule
    ADD COLUMN released_slots INT NOT NULL DEFAULT 0
        COMMENT '已放出到号池的号数。分批放号的进度，只增不减' AFTER booked_slots;

-- 演示数据里那几个排班是「已放号」状态，把进度补齐，避免看板显示成还没放号
UPDATE t_schedule SET released_slots = total_slots WHERE status = 'OPEN';
