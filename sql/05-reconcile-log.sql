-- ---------------------------------------------------------------------------
-- 对账补偿留档。
--
-- 为什么要单独一张表而不是只打日志：补偿动作会**改动号源账目**，
-- 这类动作必须可审计、可回溯——「谁在什么时候把多少号补回了哪个池」
-- 是事后核查唯一的依据。日志会滚动清理，账目改动的记录不能。
--
-- 刻意只记「真动手」「拒绝动手的告警」「dry-run 预演」三类。
-- 探测每 30 秒一次，把「账目平衡」也记下来一天就是 2880 行噪声，
-- 真正需要留档的那几行反而被埋掉——和 ConsistencyChecker 里
-- probe()/check() 分开是同一个理由。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_reconcile_log
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    pool_id       BIGINT      NOT NULL COMMENT '号池（排班）ID',

    -- 采样快照：出事后要能重建当时的账目，不能只留结论
    initial_stock INT         NOT NULL COMMENT '总号数',
    bucket_sum    INT         NOT NULL COMMENT '桶剩余',
    lease_held    INT         NOT NULL COMMENT '实例本地持有',
    holding_appts INT         NOT NULL COMMENT '占号预约数',

    vanished      INT         NOT NULL COMMENT '守恒残差。>0 少卖，<0 潜在超卖',
    acted         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否真的改动了号源',
    compensated   INT         NOT NULL DEFAULT 0 COMMENT '实际补回的号数',
    decision      VARCHAR(500) NOT NULL COMMENT '判断理由。拒绝处置的原因同样要留',

    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_pool_time (pool_id, created_at),
    -- 只查「真的改过账」的记录是最高频的审计需求
    KEY idx_acted (acted, created_at)
) ENGINE = InnoDB COMMENT '对账补偿留档';
