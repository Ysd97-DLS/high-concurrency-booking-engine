-- FlashPilot 表结构
-- 这个文件挂在 mysql 容器的 /docker-entrypoint-initdb.d 下，首次启动自动执行。
-- 改了这个文件要 `docker compose down -v` 清掉数据卷才会重新执行。

CREATE DATABASE IF NOT EXISTS flashpilot DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE flashpilot;

-- 商品与库存。MySQL 是库存的唯一权威来源，Redis 只是预扣器。
CREATE TABLE IF NOT EXISTS t_item
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(128) NOT NULL,
    total_stock INT          NOT NULL COMMENT '活动总库存',
    sold_stock  INT          NOT NULL DEFAULT 0 COMMENT '已成交数，由消费者累加',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '秒杀商品';

-- 订单。uk_user_item 是「一人一单」的最终物理保证：
-- 无论 Redis 层判重逻辑出什么问题，这个唯一索引都不会让同一个人下两单。
CREATE TABLE IF NOT EXISTS t_order
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    item_id    BIGINT      NOT NULL,
    event_id   VARCHAR(64) NOT NULL COMMENT 'Redis Stream 消息 ID，用于排查',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_item (user_id, item_id),
    KEY idx_item (item_id)
) ENGINE = InnoDB COMMENT '秒杀订单';

-- 控制面的变更审计。Agent 和规则控制器的每一次改参数都要留痕，且可回滚。
CREATE TABLE IF NOT EXISTS t_config_audit
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    version    BIGINT       NOT NULL COMMENT '变更后的配置版本号',
    param      VARCHAR(64)  NOT NULL,
    old_value  VARCHAR(128),
    new_value  VARCHAR(128),
    source     VARCHAR(32)  NOT NULL COMMENT 'L0_RULE / L1_AGENT / MANUAL / ROLLBACK',
    reason     VARCHAR(512) COMMENT '规则给出的触发条件，或 Agent 给出的归因理由',
    accepted   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 表示被护栏驳回',
    guard_note VARCHAR(256) COMMENT '护栏的处理说明：钳制到边界 / cooldown 未过 / 参数不在白名单',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_param (param)
) ENGINE = InnoDB COMMENT '控制面变更审计';

-- 一致性校验报告。每次压测结束跑一次，历史留档用来对比。
CREATE TABLE IF NOT EXISTS t_consistency_report
(
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    item_id       BIGINT   NOT NULL,
    initial_stock INT      NOT NULL,
    bucket_sum    INT      NOT NULL COMMENT 'Σ桶剩余',
    lease_held    INT      NOT NULL COMMENT 'Σ实例本地持有（含待回收租约）',
    stream_len    INT      NOT NULL COMMENT '已发出的成交事件数',
    order_count   INT      NOT NULL COMMENT 'MySQL 实际订单数',
    duplicate     INT      NOT NULL DEFAULT 0,
    dead_letter   INT      NOT NULL DEFAULT 0,
    oversold      INT      NOT NULL COMMENT '> 0 即为超卖，必须为 0',
    undersold     INT      NOT NULL COMMENT '售罄时 > 0 即为少卖',
    passed        TINYINT(1) NOT NULL,
    detail        TEXT,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_item_created (item_id, created_at)
) ENGINE = InnoDB COMMENT '一致性校验报告';

-- 一个默认商品，方便起手就能压测
INSERT INTO t_item (id, title, total_stock, sold_stock)
VALUES (1001, 'FlashPilot 测试商品 · 1000 件', 1000, 0)
ON DUPLICATE KEY UPDATE title = VALUES(title);
