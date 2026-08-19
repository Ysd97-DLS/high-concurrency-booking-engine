-- 医院预约挂号业务域表结构。设计说明见 docs/DOMAIN.md。
--
-- 与 01-schema.sql 的关系：
--   t_config_audit / t_consistency_report 是基础设施表，保留不动。
--   t_item / t_order 是原来抽象商品域的表，被这里的 t_schedule / t_appointment 取代。
--   01 里那两张表暂时留着，等阶段 1 验证通过后再删，免得中途出问题没有退路。

USE flashpilot;

-- ---------------------------------------------------------------------------
-- 静态资源：科室 / 医生
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_department
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL COMMENT '科室编码，如 NEIKE',
    name       VARCHAR(64) NOT NULL COMMENT '内科 / 外科 / 儿科…',
    sort_order INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT '科室';

-- 职称直接决定号别与挂号费，所以放在医生上而不是排班上。
CREATE TABLE IF NOT EXISTS t_doctor
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    department_id BIGINT      NOT NULL,
    name          VARCHAR(64) NOT NULL,
    title         VARCHAR(16) NOT NULL COMMENT 'CHIEF主任 / DEPUTY副主任 / ATTENDING主治 / RESIDENT住院',
    specialty     VARCHAR(256) COMMENT '擅长方向，患者端展示',
    intro         VARCHAR(1024),
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dept (department_id)
) ENGINE = InnoDB COMMENT '医生';

-- ---------------------------------------------------------------------------
-- 排班 = 库存池
--
-- 这张表是引擎里 poolId 的载体，取代原来的 t_item。
-- 「同一排班内的号完全可互换」这个性质是整套三层库存/桶间借调能成立的前提：
-- 都是李医生周三上午的普通号，谁拿到第 7 号和第 8 号在业务上没有区别。
-- 而跨排班不可互换（换医生、换时段、换号别都是另一回事），正好对应引擎
-- 「池内可借调、跨池不可」的语义。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_schedule
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '这个 id 就是引擎的 poolId',
    hospital_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '留字段但只跑单院，见 DOMAIN.md §8',
    doctor_id     BIGINT       NOT NULL,
    department_id BIGINT       NOT NULL COMMENT '冗余一份，患者端按科室筛选时免连表',
    visit_date    DATE         NOT NULL COMMENT '就诊日期',
    period        VARCHAR(8)   NOT NULL COMMENT 'AM 上午 / PM 下午',
    slot_type     VARCHAR(16)  NOT NULL COMMENT 'NORMAL普通号 / EXPERT专家号 / SPECIAL特需号',
    fee_cents     INT          NOT NULL COMMENT '挂号费，单位分。用整数避免浮点',

    total_slots   INT          NOT NULL COMMENT '总号数，等式③ 的初始值',
    booked_slots  INT          NOT NULL DEFAULT 0 COMMENT '已生效预约数，由消费者累加',

    release_at    DATETIME     NOT NULL COMMENT '放号时刻。洪峰就发生在这一秒',
    visit_start   TIME         NOT NULL COMMENT '就诊开始时间，用于推算分时段',
    visit_end     TIME         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待放号 / OPEN放号中 / CLOSED已停止 / FINISHED已结束',

    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 同一医生同一天同一时段同一号别只能有一个排班，防止运营重复建号池
    UNIQUE KEY uk_doctor_slot (doctor_id, visit_date, period, slot_type),
    KEY idx_dept_date (department_id, visit_date),
    KEY idx_release (release_at, status)
) ENGINE = InnoDB COMMENT '排班（库存池）';

-- ---------------------------------------------------------------------------
-- 患者：实名制
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_patient
(
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '这个 id 就是引擎的 holderId',
    id_card       VARCHAR(32) NOT NULL COMMENT '身份证号，实名制的锚点',
    name          VARCHAR(64) NOT NULL,
    phone         VARCHAR(20) NOT NULL,
    card_no       VARCHAR(32) COMMENT '院内就诊卡号',
    no_show_count INT         NOT NULL DEFAULT 0 COMMENT '累计失约次数，达 3 次限制预约 30 天',
    blocked_until DATETIME    COMMENT '非空且未过期则禁止预约',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_id_card (id_card),
    KEY idx_phone (phone)
) ENGINE = InnoDB COMMENT '患者';

-- ---------------------------------------------------------------------------
-- 预约单：状态机的载体
--
-- uk_active 是「一人一号」的最终物理保证：
-- 同一患者对同一医生同一天只能有一个有效预约。
-- 注意它不能简单地建成唯一索引 —— 患者退号后应该允许再约，
-- 所以唯一性只在「有效状态」上成立。MySQL 没有条件唯一索引（partial index），
-- 这里用 active_key 生成列模拟：有效时等于业务键，无效时退化成一个天然唯一、不参与冲突的值。
--
-- 无效分支<b>不能用主键 id</b>：MySQL 报 ERROR 3109「Generated column cannot refer to
-- auto-increment column」，生成列不允许引用自增列。所以用 appt_no —— 它同样唯一，
-- 而且是业务侧生成的、不依赖自增。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_appointment
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    appt_no       VARCHAR(32)  NOT NULL COMMENT '对患者展示的预约号',
    schedule_id   BIGINT       NOT NULL COMMENT '= poolId',
    patient_id    BIGINT       NOT NULL COMMENT '= holderId',
    doctor_id     BIGINT       NOT NULL,
    visit_date    DATE         NOT NULL,

    seq_no        INT          NOT NULL COMMENT '就诊序号，用于推算分时段就诊时间',
    visit_time    TIME         COMMENT '分配到的具体就诊时间点',

    status        VARCHAR(16)  NOT NULL COMMENT 'PENDING_PAY / BOOKED / EXPIRED / REFUNDED / COMPLETED / NO_SHOW',
    fee_cents     INT          NOT NULL,
    pay_deadline  DATETIME     NOT NULL COMMENT '超时释放的判据，抢号成功即写入',
    paid_at       DATETIME,
    cancelled_at  DATETIME,

    event_id      VARCHAR(64)  NOT NULL COMMENT 'Redis Stream 消息 ID，排查用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 条件唯一：只有「占着号」的状态才参与一人一号约束。
    -- PENDING_PAY 和 BOOKED 占号；EXPIRED / REFUNDED 已归还，不该阻止患者重新预约。
    active_key VARCHAR(96) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING_PAY', 'BOOKED', 'COMPLETED', 'NO_SHOW')
             THEN CONCAT(patient_id, '-', doctor_id, '-', visit_date)
             ELSE CONCAT('void-', appt_no)
        END
    ) STORED,

    PRIMARY KEY (id),
    UNIQUE KEY uk_appt_no (appt_no),
    UNIQUE KEY uk_active (active_key),
    KEY idx_schedule (schedule_id),
    KEY idx_patient (patient_id, created_at),
    KEY idx_deadline (status, pay_deadline) COMMENT '超时扫描走这个索引'
) ENGINE = InnoDB COMMENT '预约单';

-- ---------------------------------------------------------------------------
-- 风控：设备与命中记录
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_risk_event
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    patient_id  BIGINT,
    device_id   VARCHAR(64) COMMENT '设备指纹',
    ip          VARCHAR(64),
    schedule_id BIGINT,
    level       VARCHAR(16) NOT NULL COMMENT 'L1频次 / L2行为 / L3画像',
    action      VARCHAR(16) NOT NULL COMMENT 'THROTTLE限流 / DEMOTE降权 / BLOCK拉黑',
    reason      VARCHAR(256) NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_patient (patient_id, created_at),
    KEY idx_device (device_id, created_at)
) ENGINE = InnoDB COMMENT '风控命中记录';

-- ---------------------------------------------------------------------------
-- 演示数据：一个科室、三名医生、若干排班
-- 放号时刻刻意设成「过去」，这样压测时可以立刻抢，不用等
-- ---------------------------------------------------------------------------

INSERT INTO t_department (id, code, name, sort_order) VALUES
    (1, 'NEIKE', '内科', 1),
    (2, 'WAIKE', '外科', 2),
    (3, 'ERKE',  '儿科', 3)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO t_doctor (id, department_id, name, title, specialty) VALUES
    (101, 1, '李建国', 'CHIEF',     '高血压、冠心病、心力衰竭'),
    (102, 1, '王秀兰', 'DEPUTY',    '糖尿病、甲状腺疾病'),
    (103, 1, '张伟',   'ATTENDING', '呼吸道感染、慢性咳嗽'),
    (201, 2, '陈明远', 'CHIEF',     '腹腔镜微创手术'),
    (301, 3, '刘芳',   'DEPUTY',    '小儿发热、生长发育')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 主任号 50 个（最抢手），副主任 80，主治 150。
-- 压测时用 schedule_id 1001 这个大池子，跟原来的 itemId=1001 对齐，方便对照历史数据。
INSERT INTO t_schedule
    (id, doctor_id, department_id, visit_date, period, slot_type, fee_cents,
     total_slots, release_at, visit_start, visit_end, status)
VALUES
    (1001, 101, 1, CURRENT_DATE + INTERVAL 1 DAY, 'AM', 'EXPERT', 5000,
     50,  CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '08:00:00', '11:30:00', 'OPEN'),
    (1002, 102, 1, CURRENT_DATE + INTERVAL 1 DAY, 'AM', 'EXPERT', 3000,
     80,  CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '08:00:00', '11:30:00', 'OPEN'),
    (1003, 103, 1, CURRENT_DATE + INTERVAL 1 DAY, 'AM', 'NORMAL', 1500,
     150, CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '08:00:00', '11:30:00', 'OPEN'),
    (1004, 201, 2, CURRENT_DATE + INTERVAL 1 DAY, 'PM', 'EXPERT', 5000,
     40,  CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '14:00:00', '17:00:00', 'OPEN'),
    (1005, 301, 3, CURRENT_DATE + INTERVAL 1 DAY, 'AM', 'NORMAL', 1500,
     120, CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '08:00:00', '11:30:00', 'OPEN')
ON DUPLICATE KEY UPDATE total_slots = VALUES(total_slots), status = VALUES(status);

-- 压测专用大池子：号数由 /verify/preheat 动态改写，这里只占位
INSERT INTO t_schedule
    (id, doctor_id, department_id, visit_date, period, slot_type, fee_cents,
     total_slots, release_at, visit_start, visit_end, status)
VALUES
    (9999, 101, 1, CURRENT_DATE + INTERVAL 7 DAY, 'AM', 'NORMAL', 1500,
     100000, CURRENT_TIMESTAMP - INTERVAL 1 MINUTE, '08:00:00', '11:30:00', 'OPEN')
ON DUPLICATE KEY UPDATE total_slots = VALUES(total_slots);
