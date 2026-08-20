package com.flashpilot.clinic.admin.mapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 运营端与放号的数据访问。SQL 在 {@code resources/mapper/AdminMapper.xml}。
 *
 * <p>这个 mapper 集中了迁移里最能体现 MyBatis 价值的两处：
 * <ul>
 *   <li>{@link #listSchedules} 的可选日期过滤 —— 原来是 Java 里字符串拼
 *       {@code sql + (date == null ? "" : " WHERE ... ")}，再按 date 是否为空
 *       决定调哪个重载。现在是 {@code <if>}，一条语句两种形态。</li>
 *   <li>{@link #createSchedule} 的自增主键回填 —— 原来要手写
 *       {@code PreparedStatementCreator} + {@code GeneratedKeyHolder}，
 *       九个 {@code ps.setXxx(i, ...)} 全靠下标对齐。现在是
 *       {@code useGeneratedKeys="true"}，主键直接写回入参。</li>
 * </ul>
 */
public interface AdminMapper {

    /** 排班列表，带放号进度。{@code date} 为 null 时列全部。 */
    List<Map<String, Object>> listSchedules(@Param("date") LocalDate date);

    /**
     * 建排班，自增主键回填到 {@code params} 的 {@code id}。
     *
     * <p>必须能拿回主键：运营端建完排班后的所有操作（开放放号、查进度、停止）
     * 都要用它。第一版只返回 {ok, message}，实测调
     * {@code POST /admin/schedules//open} 直接 404 ——
     * 这类「写成功了但不告诉你写的是哪一条」的缺陷，单接口 curl 测不出来。
     */
    int createSchedule(NewSchedule params);

    /** 建排班的入参。{@code id} 由 MyBatis 回填，不需要调用方赋值。 */
    class NewSchedule {
        private Long id;
        private long doctorId;
        private LocalDate visitDate;
        private String period;
        private String slotType;
        private int feeCents;
        private int totalSlots;
        private LocalTime visitStart;
        private LocalTime visitEnd;

        public NewSchedule(long doctorId, LocalDate visitDate, String period, String slotType,
                           int feeCents, int totalSlots, LocalTime visitStart, LocalTime visitEnd) {
            this.doctorId = doctorId;
            this.visitDate = visitDate;
            this.period = period;
            this.slotType = slotType;
            this.feeCents = feeCents;
            this.totalSlots = totalSlots;
            this.visitStart = visitStart;
            this.visitEnd = visitEnd;
        }

        // MyBatis 需要 getter 取值、setId 回填主键。
        // 这里没用 record 正是因为 record 不可变，装不下「回填」这个动作。
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getDoctorId() { return doctorId; }
        public LocalDate getVisitDate() { return visitDate; }
        public String getPeriod() { return period; }
        public String getSlotType() { return slotType; }
        public int getFeeCents() { return feeCents; }
        public int getTotalSlots() { return totalSlots; }
        public LocalTime getVisitStart() { return visitStart; }
        public LocalTime getVisitEnd() { return visitEnd; }
    }

    List<Map<String, Object>> statusBreakdown(@Param("scheduleId") long scheduleId);

    List<Map<String, Object>> riskEvents(@Param("limit") int limit);

    List<Map<String, Object>> blockedPatients();

    int unblock(@Param("id") long id);

    // ---------- 放号 ----------

    Map<String, Object> scheduleReleaseState(@Param("scheduleId") long scheduleId);

    Map<String, Object> scheduleProgress(@Param("scheduleId") long scheduleId);

    int openSchedule(@Param("scheduleId") long scheduleId);

    int closeSchedule(@Param("scheduleId") long scheduleId);

    /** 放号：带 total 上限的条件更新，物理上放不出超过总数的号。 */
    int releaseSlots(@Param("scheduleId") long scheduleId, @Param("amount") int amount);

    /** 放号回滚（Redis 侧写失败时）。下限保护到 0。 */
    int rollbackReleased(@Param("scheduleId") long scheduleId, @Param("n") int n);
}
