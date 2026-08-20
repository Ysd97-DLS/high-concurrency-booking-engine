package com.flashpilot.clinic.domain.mapper;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.flashpilot.clinic.domain.ScheduleRepository.PoolInfo;

/**
 * 排班（= 库存池）的数据访问。SQL 在 {@code resources/mapper/ScheduleMapper.xml}。
 *
 * <p>几个面向前端的查询刻意返回 {@code Map} 而不是领域对象：它们是
 * 「拼给页面看的视图」，字段随页面需要增删，定义成 record 反而会让每次改页面
 * 都要动一个领域类型。这条边界和 {@link #find} 返回 {@link PoolInfo} 是对照的 ——
 * 后者是抢号热路径要用的领域数据，必须是强类型。
 */
public interface ScheduleMapper {

    /** 号池静态信息。抢号热路径用（算就诊时间、写挂号费），会被缓存。 */
    PoolInfo find(@Param("scheduleId") long scheduleId);

    /** 患者端：按科室和日期列可约排班，带医生信息。 */
    List<Map<String, Object>> listOpen(@Param("departmentId") long departmentId,
                                       @Param("date") LocalDate date);

    List<Map<String, Object>> listDepartments();

    /**
     * 「我的预约」的补充信息：排班 → 医生 / 科室 / 时段。
     *
     * <p><b>批量查而不是逐条查。</b>列表页一次展示 20-30 条预约，
     * 逐条查医生就是典型的 N+1：30 条预约 = 31 次往返。
     */
    List<Map<String, Object>> viewByIds(@Param("scheduleIds") Collection<Long> scheduleIds);

    /** 需要对账的号池：已放过号、且就诊日还没过去。 */
    List<Long> poolsNeedingReconcile();

    int addBookedSlots(@Param("scheduleId") long scheduleId, @Param("delta") int delta);

    Integer bookedSlots(@Param("scheduleId") long scheduleId);

    Integer totalSlots(@Param("scheduleId") long scheduleId);
}
