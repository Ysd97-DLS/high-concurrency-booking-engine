package com.flashpilot.clinic.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.flashpilot.clinic.domain.mapper.ScheduleMapper;

/**
 * 排班（= 库存池）仓储。
 *
 * <p>排班 ID 就是引擎的 {@code poolId}。这张表是"号源可互换"这个前提的载体：
 * 同一排班内的号都是同一位医生、同一天、同一时段、同一号别，业务上完全等价，
 * 所以引擎的桶间借调、本地号段、租约回收才能成立。
 */
@Repository
public class ScheduleRepository {

    private final ScheduleMapper mapper;

    public ScheduleRepository(ScheduleMapper mapper) {
        this.mapper = mapper;
    }

    /** 号池的静态信息，抢号时要用（算就诊时间、写挂号费）。缓存在内存里避免每请求查库。 */
    public record PoolInfo(
            long scheduleId, long doctorId, long departmentId,
            LocalDate visitDate, String period, String slotType,
            int feeCents, int totalSlots,
            LocalTime visitStart, LocalTime visitEnd, String status) {

        /**
         * 按就诊序号推算具体就诊时间。
         *
         * <p>号源在排班维度可互换（引擎只管数量），但发号成功后要给患者一个确定的到院时间，
         * 否则所有人 8:00 一起到，分时段就没意义了。
         * 这一步刻意放在引擎之外——引擎保持纯计数语义。
         */
        public LocalTime visitTimeOf(int seqNo) {
            if (totalSlots <= 0) {
                return visitStart;
            }
            long totalMinutes = java.time.Duration.between(visitStart, visitEnd).toMinutes();
            // 每 15 分钟一个批次，把号平均分到各批次里
            long batches = Math.max(1, totalMinutes / 15);
            long perBatch = Math.max(1, (long) Math.ceil(totalSlots / (double) batches));
            long batchIndex = Math.min(batches - 1, Math.max(0, (seqNo - 1) / perBatch));
            return visitStart.plusMinutes(batchIndex * 15);
        }
    }

    public PoolInfo find(long scheduleId) {
        return mapper.find(scheduleId);
    }

    /** 患者端：按科室和日期列可约排班，带医生信息。 */
    public List<Map<String, Object>> listOpen(long departmentId, LocalDate date) {
        return mapper.listOpen(departmentId, date);
    }

    public List<Map<String, Object>> listDepartments() {
        return mapper.listDepartments();
    }

    /**
     * 「我的预约」用的补充信息：排班 ID → 医生 / 科室 / 时段。
     *
     * <p>为什么需要这个方法：{@code t_appointment} 只存 {@code doctor_id} 和
     * {@code schedule_id}，不冗余医生姓名。于是「我的预约」页面只能显示
     * 「A20006-1 / 08:15 / 50 元 / 待支付」——<b>看不出这是哪位医生、哪个科室</b>，
     * 而这恰恰是患者最需要确认的信息。
     *
     * <p><b>刻意做成批量查询而不是逐条查。</b>列表页一次展示 20-30 条预约，
     * 逐条查医生就是典型的 N+1：30 条预约 = 31 次数据库往返。
     * 这里用一次 {@code IN} 查询把所有排班的信息取回来，在内存里拼。
     *
     * @param scheduleIds 去重后的排班 ID；空集合直接返回空 Map，不发查询
     */
    public Map<Long, Map<String, Object>> viewByIds(Collection<Long> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : mapper.viewByIds(scheduleIds)) {
            byId.put(((Number) row.get("scheduleId")).longValue(), row);
        }
        return byId;
    }

    /** 消费者攒批落库后累加已生效号数。一批一次 UPDATE，避免十万行抢同一把行锁。 */
    /**
     * 需要对账的号池：已经放过号、且就诊日期还没过去的排班。
     *
     * <p>「已放过号」是必要条件 —— 没放号的排班 Redis 里没有桶，账目恒等于零，
     * 对它们做校验只是白跑一遍 32 个桶的查询。
     * 「日期没过去」是因为过期排班的号源已经失去价值（号源是「某时间点某医生的接诊能力」），
     * 补回桶里也没人能用。
     *
     * <p>按 id 排序是为了让轮转扫描的顺序稳定可预测 —— 对账每轮只扫一部分，
     * 顺序飘的话某些排班可能长期扫不到。
     */
    public List<Long> poolsNeedingReconcile() {
        return mapper.poolsNeedingReconcile();
    }

    public void addBookedSlots(long scheduleId, int delta) {
        mapper.addBookedSlots(scheduleId, delta);
    }

    public int bookedSlots(long scheduleId) {
        Integer v = mapper.bookedSlots(scheduleId);
        return v == null ? 0 : v;
    }

    public int totalSlots(long scheduleId) {
        Integer v = mapper.totalSlots(scheduleId);
        return v == null ? 0 : v;
    }
}
