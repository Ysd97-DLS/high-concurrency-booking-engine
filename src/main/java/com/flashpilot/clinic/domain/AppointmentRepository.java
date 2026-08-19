package com.flashpilot.clinic.domain;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 预约单持久化。
 *
 * <p><b>所有状态转移都用「带旧状态条件的 UPDATE」实现，而不是先查再改。</b>
 * 原因：超时释放任务和患者支付会并发命中同一张单——
 * 一个想把它变成 EXPIRED 并归还号源，另一个想变成 BOOKED。
 * 先读后写的话两边都会认为自己该赢，结果要么号源被归还了但患者也支付成功了（超卖），
 * 要么患者付了钱却拿不到号。用 {@code WHERE status = ?} 让数据库来裁决，
 * 谁的 affected rows 是 1 谁就赢，另一边必须放弃。
 */
@Repository
public class AppointmentRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AppointmentRepository.class);

    private final JdbcTemplate jdbc;

    public AppointmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = """
            id, appt_no, schedule_id, patient_id, doctor_id, visit_date, seq_no, visit_time,
            status, fee_cents, pay_deadline, paid_at, cancelled_at, event_id, created_at
            """;

    private final RowMapper<Appointment> mapper = (rs, n) -> new Appointment(
            rs.getLong("id"),
            rs.getString("appt_no"),
            rs.getLong("schedule_id"),
            rs.getLong("patient_id"),
            rs.getLong("doctor_id"),
            rs.getObject("visit_date", LocalDate.class),
            rs.getInt("seq_no"),
            rs.getObject("visit_time", LocalTime.class),
            ApptStatus.valueOf(rs.getString("status")),
            rs.getInt("fee_cents"),
            toLdt(rs.getTimestamp("pay_deadline")),
            toLdt(rs.getTimestamp("paid_at")),
            toLdt(rs.getTimestamp("cancelled_at")),
            rs.getString("event_id"),
            toLdt(rs.getTimestamp("created_at")));

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ---------- 写 ----------

    /**
     * 批量插入待支付预约单。由 Stream 消费者攒批调用。
     *
     * <p>用 {@code INSERT IGNORE} 吃掉两类重复：消息重投，以及一人一号的
     * {@code uk_active} 冲突。两者都不该让整批失败。
     * 返回实际插入的行数，调用方据此累加"已生效"计数。
     */
    public int insertPendingBatch(List<PendingAppt> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder("""
                INSERT IGNORE INTO t_appointment
                  (appt_no, schedule_id, patient_id, doctor_id, visit_date, seq_no, visit_time,
                   status, fee_cents, pay_deadline, event_id)
                VALUES
                """);
        // 每行 10 个占位符（status 是字面量 'PENDING_PAY'，不占位）
        Object[] args = new Object[batch.size() * 10];
        int i = 0;
        for (int k = 0; k < batch.size(); k++) {
            sb.append(k == 0 ? "" : ",").append("(?,?,?,?,?,?,?,'PENDING_PAY',?,?,?)");
            PendingAppt p = batch.get(k);
            args[i++] = p.apptNo();
            args[i++] = p.scheduleId();
            args[i++] = p.patientId();
            args[i++] = p.doctorId();
            args[i++] = p.visitDate();
            args[i++] = p.seqNo();
            args[i++] = p.visitTime();
            args[i++] = p.feeCents();
            args[i++] = p.payDeadline();
            args[i++] = p.eventId();
        }
        return jdbc.update(sb.toString(), args);
    }

    /**
     * 这批患者里，哪些已经有占号的预约单。
     *
     * <p>存在的理由是 {@code INSERT IGNORE} 的一个根本缺陷：它对「真重复」和
     * 「其它任何约束失败」返回<b>完全相同</b>的结果（该行不插入、不报错）。
     * 调用方只能看到「插了几行」，于是原来用减法推断重复数 ——
     * <b>把所有插不进去的行都当成重复</b>。
     *
     * <p>后果实测过（P6 Redis 主从切换）：19389 个号从 Redis 桶扣走了，
     * Stream 里有它们的记录，MySQL 里一条都没有，而系统把它们全计为「重复购买」
     * 并 ACK 掉 —— 不重试、不进死信、日志一片干净。
     * 拿缺失的 holderId 手工试插能成功，证明它们并不是真重复。
     *
     * <p>所以插入行数不足时必须回来查一次：查到的是真重复，查不到的是真失败，
     * 后者绝不能 ACK。查询走 {@code uk_active} 索引，只在异常路径上执行。
     */
    public java.util.Set<Long> findHoldingPatients(long scheduleId, java.util.List<Long> patientIds) {
        if (patientIds.isEmpty()) {
            return java.util.Set.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(patientIds.size(), "?"));
        Object[] args = new Object[patientIds.size() + 1];
        args[0] = scheduleId;
        for (int i = 0; i < patientIds.size(); i++) {
            args[i + 1] = patientIds.get(i);
        }
        java.util.List<Long> found = jdbc.queryForList("""
                SELECT patient_id FROM t_appointment
                 WHERE schedule_id=? AND patient_id IN (%s)
                   AND status IN ('PENDING_PAY','BOOKED','COMPLETED','NO_SHOW')
                """.formatted(marks), Long.class, args);
        return new java.util.HashSet<>(found);
    }

    /** 这个排班已经用掉的最大就诊序号。序号校准时以它为权威。 */
    public int maxSeqNo(long scheduleId) {
        java.util.List<Integer> r = jdbc.queryForList(
                "SELECT MAX(seq_no) FROM t_appointment WHERE schedule_id=?", Integer.class, scheduleId);
        return r.isEmpty() || r.get(0) == null ? 0 : r.get(0);
    }

    /**
     * 这批取号凭证里，有多少个已经被占用了。
     *
     * <p>诊断用。{@code INSERT IGNORE} 整批返回 0 插入、0 重复时（实测 P6 主从切换后
     * 反复出现「批大小=256 插入=0 重复=0」），唯一能解释的就是 {@code uk_appt_no} 冲突 ——
     * 而 appt_no 里的序号来自 Redis 的 {@code INCRBY}，<b>异步复制的从库提主时那个序号会回退</b>，
     * 回退区间内生成的凭证号就会撞上已经落库的行。
     *
     * <p>没有这个查询，日志只能说「插不进去」，说不出「因为凭证号重了」——
     * 而这两句话指向完全不同的修复方向。
     */
    public int countExistingApptNos(java.util.List<String> apptNos) {
        if (apptNos.isEmpty()) {
            return 0;
        }
        String marks = String.join(",", java.util.Collections.nCopies(apptNos.size(), "?"));
        java.util.List<Integer> r = jdbc.queryForList(
                "SELECT COUNT(*) FROM t_appointment WHERE appt_no IN (%s)".formatted(marks),
                Integer.class, apptNos.toArray());
        return r.isEmpty() || r.get(0) == null ? 0 : r.get(0);
    }

    /** 支付：PENDING_PAY → BOOKED。返回 true 表示这次调用赢了。 */
    public boolean markPaid(String apptNo, LocalDateTime now) {
        return jdbc.update("""
                UPDATE t_appointment SET status='BOOKED', paid_at=?
                 WHERE appt_no=? AND status='PENDING_PAY'
                """, now, apptNo) == 1;
    }

    /** 退号：BOOKED → REFUNDED。号源归还由调用方在这之后做。 */
    public boolean markRefunded(String apptNo, LocalDateTime now) {
        return jdbc.update("""
                UPDATE t_appointment SET status='REFUNDED', cancelled_at=?
                 WHERE appt_no=? AND status='BOOKED'
                """, now, apptNo) == 1;
    }

    /** 超时：PENDING_PAY → EXPIRED。号源归还由调用方在这之后做。 */
    public boolean markExpired(String apptNo, LocalDateTime now) {
        return jdbc.update("""
                UPDATE t_appointment SET status='EXPIRED', cancelled_at=?
                 WHERE appt_no=? AND status='PENDING_PAY'
                """, now, apptNo) == 1;
    }

    /** 就诊完成：BOOKED → COMPLETED。 */
    public boolean markCompleted(String apptNo) {
        return jdbc.update("""
                UPDATE t_appointment SET status='COMPLETED'
                 WHERE appt_no=? AND status='BOOKED'
                """, apptNo) == 1;
    }

    /**
     * 失约：BOOKED → NO_SHOW，同时累加患者失约次数。不归还号源。
     *
     * <h3>关于「服完禁约之后」的语义 —— 这是一个刻意保留的决定，不是遗漏</h3>
     *
     * 封禁到期后患者会<b>自动</b>恢复预约（黑名单每 30 秒按 {@code blocked_until > NOW()}
     * 整体重建，到期的自然掉出，实测确认过）。但 {@code no_show_count} <b>不会归零</b>，
     * 于是这个患者<b>再失约一次就会立刻被再封 30 天</b>（因为 4 &gt;= 3），并且永远如此。
     *
     * <p>两种解读都说得通，取舍不该由代码默默决定：
     * <ul>
     *   <li><b>当前行为（累犯从严）</b>：失约记录是长期信用，到过 3 次之后每次失约都重新封。
     *       对号源紧张的专家号是合理的 —— 反复占号不来的人确实该被持续约束。</li>
     *   <li><b>另一种（服完即归零）</b>：禁约 30 天就是这 3 次的代价，服完重新开始。
     *       更符合「惩罚有限期」的直觉，但对惯犯几乎没有约束力
     *       —— 每 30 天只需付一次代价。</li>
     * </ul>
     *
     * <p>要改成后者，就在黑名单刷新时把已到期的记录清零（而不是只让它掉出黑名单）。
     * <b>运营解封接口走的已经是后者的语义</b>（{@code no_show_count=0}），
     * 也就是说人工解封比自动到期更宽容 —— 这是有意的：
     * 人工解封意味着有人核查过情况，而自动到期只是时间到了。
     *
     * @return 状态是否真的从 BOOKED 改成了 NO_SHOW
     */
    public boolean markNoShow(String apptNo) {
        int n = jdbc.update("""
                UPDATE t_appointment SET status='NO_SHOW'
                 WHERE appt_no=? AND status='BOOKED'
                """, apptNo);
        if (n == 1) {
            int counted = jdbc.update("""
                    UPDATE t_patient p
                       SET p.no_show_count = p.no_show_count + 1,
                           p.blocked_until = CASE WHEN p.no_show_count + 1 >= 3
                                                  THEN DATE_ADD(NOW(), INTERVAL 30 DAY)
                                                  ELSE p.blocked_until END
                     WHERE p.id = (SELECT a.patient_id FROM t_appointment a WHERE a.appt_no=?)
                    """, apptNo);
            if (counted != 1) {
                // 这个 UPDATE 以前<b>没人看它的返回值</b>，而它影响 0 行是完全可能的：
                // patient_id 是抢号时传进来的 holderId，t_patient 里不一定有这个人
                // （患者记录被删、数据迁移不一致，或者干脆是压测/演示数据）。
                //
                // 后果很隐蔽：预约单确实变成了 NO_SHOW，方法也返回 true，
                // 但**患者的失约次数没有增加** —— 而累计 3 次失约是
                // 整个风控体系里唯一会「真正拒绝」的判据（其余都只是降权进慢车道）。
                // 也就是说这个患者<b>无论失约多少次都不会被限制</b>，而日志里一个字都没有。
                log.error("失约计数没记上：apptNo={} 已标记 NO_SHOW，但 t_patient 里更新了 {} 行。"
                        + "该患者的失约次数不会增加，累计 3 次禁约 30 天对他永久失效。"
                        + "查 t_appointment.patient_id 是否在 t_patient 里存在",
                        apptNo, counted);
            }
        }
        return n == 1;
    }

    // ---------- 读 ----------

    /**
     * 捞出就诊日已经过去、却仍是 BOOKED 的单 —— 也就是失约候选。走 idx_visit_status 索引。
     *
     * <p><b>判据刻意保守：只看「就诊日已过」，不精确到时段结束时间。</b>
     * 两个方向的代价严重不对称：
     * <ul>
     *   <li>判<b>早</b>了 —— 把还能来就诊的患者标成失约，而失约累计 3 次就是 30 天禁约。
     *       这是「惩罚真实患者」，在挂号场景里代价极高：被误封的人不会投诉，只是挂不上号。</li>
     *   <li>判<b>晚</b>了 —— 失约统计晚几个小时生效。没有任何业务影响。</li>
     * </ul>
     * 所以取「最迟第二天 0 点之后才判」。精确到时段（AM 12:00 结束）能早几个小时，
     * 但那点提前量换不来任何东西，却把误伤的窗口打开了。
     *
     * @param beforeDate 就诊日<b>严格早于</b>这个日期的才算候选。传今天即「昨天及更早」。
     */
    public List<Appointment> findNoShowCandidates(LocalDate beforeDate, int limit) {
        return jdbc.query("SELECT " + COLS + """
                  FROM t_appointment
                 WHERE visit_date < ? AND status='BOOKED'
                 ORDER BY visit_date
                 LIMIT ?
                """, mapper, beforeDate, limit);
    }

    /** 捞出已超时的待支付单。走 idx_deadline 索引。 */
    public List<Appointment> findExpiredPending(LocalDateTime now, int limit) {
        return jdbc.query("SELECT " + COLS + """
                  FROM t_appointment
                 WHERE status='PENDING_PAY' AND pay_deadline < ?
                 ORDER BY pay_deadline
                 LIMIT ?
                """, mapper, now, limit);
    }

    public List<Appointment> findByPatient(long patientId, int limit) {
        return jdbc.query("SELECT " + COLS + """
                  FROM t_appointment WHERE patient_id=? ORDER BY id DESC LIMIT ?
                """, mapper, patientId, limit);
    }

    public Appointment findByApptNo(String apptNo) {
        List<Appointment> l = jdbc.query("SELECT " + COLS + " FROM t_appointment WHERE appt_no=?",
                mapper, apptNo);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 按状态统计某个号池，供一致性校验用。 */
    public int countByStatus(long scheduleId, ApptStatus status) {
        List<Integer> r = jdbc.queryForList("""
                SELECT COUNT(*) FROM t_appointment WHERE schedule_id=? AND status=?
                """, Integer.class, scheduleId, status.name());
        return r.isEmpty() || r.get(0) == null ? 0 : r.get(0);
    }

    /**
     * 占着号源的预约数（PENDING_PAY + BOOKED + COMPLETED + NO_SHOW）。
     *
     * <p>这个数字是一致性等式③ 的一项。<b>刻意不含 EXPIRED / REFUNDED</b>——
     * 它们的号已经还回桶了，会被「Σ桶剩余」计入，再算一遍就重复了。
     */
    public int countHoldingSlots(long scheduleId) {
        List<Integer> r = jdbc.queryForList("""
                SELECT COUNT(*) FROM t_appointment
                 WHERE schedule_id=? AND status IN ('PENDING_PAY','BOOKED','COMPLETED','NO_SHOW')
                """, Integer.class, scheduleId);
        return r.isEmpty() || r.get(0) == null ? 0 : r.get(0);
    }

    /** 待支付预占数，单列出来是因为等式③ 要能看出「号卡在哪一环」。 */
    public int countPendingPay(long scheduleId) {
        return countByStatus(scheduleId, ApptStatus.PENDING_PAY);
    }

    // ---------- 实验支持 ----------

    /**
     * 把排班重置成「可以重新跑一轮」的状态。
     *
     * <p><b>{@code released_slots} 必须一起清零。</b>这里踩过一个坑：原来只重置了
     * {@code booked_slots} 和 {@code total_slots}，放号进度留着上一轮的值。
     * 于是把 preheat 指向一个演示排班时（如 {@code totalStock=50} 而上一轮放过 150），
     * 数据库里就留下 {@code released_slots=150 > total_slots=50} 这种不可能的状态，
     * 后果是：
     * <ul>
     *   <li>放号进度显示 300%；</li>
     *   <li>{@code ReleaseService.open()} 因为 {@code released >= total} 永久拒绝放号，
     *       这个排班再也放不出号，只能手工改库修复。</li>
     * </ul>
     *
     * <p>预热之后号池由 {@code stockRedis.preheat()} 直接灌满（不走放号流程），
     * 所以 {@code released_slots} 的正确取值就是 {@code total_slots}——
     * 号确实已经全部在池子里了。写 0 会让看板显示「一个号都没放」而患者却抢得到，
     * 同样是自相矛盾的状态。
     */
    public void resetForExperiment(long scheduleId, int totalSlots) {
        jdbc.update("DELETE FROM t_appointment WHERE schedule_id=?", scheduleId);
        jdbc.update("UPDATE t_schedule SET booked_slots=0, total_slots=?, released_slots=? WHERE id=?",
                totalSlots, totalSlots, scheduleId);
    }

    /** 待插入的预约单。字段顺序与 insertPendingBatch 的占位符一致。 */
    public record PendingAppt(
            String apptNo, long scheduleId, long patientId, long doctorId,
            LocalDate visitDate, int seqNo, LocalTime visitTime,
            int feeCents, LocalDateTime payDeadline, String eventId) {
    }
}
