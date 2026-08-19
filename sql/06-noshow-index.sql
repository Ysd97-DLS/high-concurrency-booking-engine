-- 失约扫描用的索引。
--
-- 背景：六状态机里 NO_SHOW 这个状态**原来只能靠人工调 HTTP 接口进入** ——
-- markNoShow 唯一的调用者是 POST /clinic/appointments/{apptNo}/no-show，
-- 没有任何定时任务，而文档（DOMAIN.md §3.2、实验报告第 04 节）
-- 明确写着「就诊时段结束扫描」。
--
-- 后果是一条完整的失效链：
--   患者没来就诊 → 单子永远停在 BOOKED → no_show_count 永不增加
--   → blocked_until 永不设置 → **失约黑名单永远是空的**
-- 而黑名单是整个风控体系里唯一会「真正拒绝」的手段（其余都是降权进慢车道）。
-- 实测确认过：t_patient 里 no_show_count > 0 的有 0 个，blocked_until 非空的有 0 个。
--
-- 扫描的判据是 `status='BOOKED' AND visit_date < CURDATE()`，
-- 而现有索引都不匹配：
--   idx_schedule  (schedule_id)
--   idx_patient   (patient_id, created_at)
--   idx_deadline  (status, pay_deadline)   ← status 前缀能用上，但 pay_deadline 帮不上忙
--
-- 列顺序取 (visit_date, status) 而不是 (status, visit_date)：
-- visit_date 的区分度高得多（每天一个值，且扫描只关心「已经过去的那些」，
-- 是一个很窄的范围扫描），而 status 只有 6 个取值。
-- 把选择性高的列放前面，范围扫描才能尽早收窄。

ALTER TABLE t_appointment
    ADD INDEX idx_visit_status (visit_date, status);
