package com.flashpilot.clinic.domain.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.flashpilot.clinic.domain.Appointment;
import com.flashpilot.clinic.domain.AppointmentRepository.PendingAppt;

/**
 * 预约单的数据访问。SQL 在 {@code resources/mapper/AppointmentMapper.xml}。
 *
 * <p><b>所有状态转移都是「带旧状态条件的 UPDATE」，返回受影响行数而不是布尔。</b>
 * 把「1 行算赢」这个判断留在 Repository 层：Mapper 只忠实反映数据库做了什么，
 * 语义解释是上一层的事。这条边界在并发路径上很重要 ——
 * 超时释放任务和患者支付会并发命中同一张单，谁的 affected rows 是 1 谁赢。
 */
public interface AppointmentMapper {

    // ---------- 写 ----------

    /** 批量插入待支付单。{@code INSERT IGNORE} 吃掉消息重投与一人一号冲突两类重复。 */
    int insertPendingBatch(@Param("batch") List<PendingAppt> batch);

    int markPaid(@Param("apptNo") String apptNo, @Param("now") LocalDateTime now);

    int markRefunded(@Param("apptNo") String apptNo, @Param("now") LocalDateTime now);

    int markExpired(@Param("apptNo") String apptNo, @Param("now") LocalDateTime now);

    int markCompleted(@Param("apptNo") String apptNo);

    int markNoShow(@Param("apptNo") String apptNo);

    /** 累加失约次数，满 3 次则禁约 30 天。返回受影响行数 —— 0 行意味着这个患者不在 t_patient 里。 */
    int bumpNoShowCount(@Param("apptNo") String apptNo);

    // ---------- 读 ----------

    /**
     * 这批患者里，哪些已经有占号的预约单。
     *
     * <p>{@code INSERT IGNORE} 对「真重复」和「其它约束失败」返回完全相同的结果，
     * 所以插入行数不足时必须回来查一次：查到的是真重复，查不到的是真失败，后者绝不能 ACK。
     */
    List<Long> findHoldingPatients(@Param("scheduleId") long scheduleId,
                                   @Param("patientIds") List<Long> patientIds);

    /** 这批凭证号里有多少个已被占用。诊断 {@code uk_appt_no} 冲突用。 */
    int countExistingApptNos(@Param("apptNos") List<String> apptNos);

    /** 这个排班已用掉的最大就诊序号。序号校准时以它为权威。可能为 null（一单都没有）。 */
    Integer maxSeqNo(@Param("scheduleId") long scheduleId);

    List<Appointment> findNoShowCandidates(@Param("beforeDate") LocalDate beforeDate,
                                           @Param("limit") int limit);

    List<Appointment> findExpiredPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    List<Appointment> findByPatient(@Param("patientId") long patientId, @Param("limit") int limit);

    Appointment findByApptNo(@Param("apptNo") String apptNo);

    int countByStatus(@Param("scheduleId") long scheduleId, @Param("status") String status);

    /** 占着号源的预约数。<b>刻意不含 EXPIRED / REFUNDED</b>——它们的号已还回桶，会被「Σ桶剩余」计入。 */
    int countHoldingSlots(@Param("scheduleId") long scheduleId);

    // ---------- 实验支持 ----------

    int deleteBySchedule(@Param("scheduleId") long scheduleId);

    int resetSchedule(@Param("scheduleId") long scheduleId, @Param("totalSlots") int totalSlots);
}
