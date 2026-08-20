package com.flashpilot.clinic.domain.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 排班占号数的增减与账目读取。SQL 在 {@code resources/mapper/ApptPersistMapper.xml}。
 *
 * <p>这些语句都很短，但每一条都在号源账目的关键路径上 ——
 * {@code booked_slots} 是患者端「剩余 X 号」的数据源，也是一致性等式⑤ 的一项，
 * 加错或漏减都会让它永久漂移。
 */
public interface ApptPersistMapper {

    /**
     * 批量累加已生效号数，<b>带 total 上限的条件更新</b>。
     *
     * <p>{@code WHERE booked + n <= total} 让「把 booked 加超过 total」在物理上不可能，
     * 这是超卖的最后一道防线 —— 即使 Redis 侧全乱了也守得住。
     */
    int incrementBookedBy(@Param("poolId") long poolId, @Param("n") int n);

    /** 号源归还时减回去，下限保护到 0：即使上游重复调用也不会变成负数。 */
    int decrementBookedBy(@Param("poolId") long poolId, @Param("n") int n);

    Integer countAllAppts(@Param("poolId") long poolId);

    /** <b>全部</b>号池的预约行数。等式⑤ 的累计量组要拿它和全局消费计数器比。 */
    Integer countAllApptsGlobal();

    /** 回滚单张（抢号后落库前失败的补偿路径）。 */
    int deleteAppt(@Param("holderId") long holderId, @Param("poolId") long poolId);

    Integer totalSlots(@Param("poolId") long poolId);

    Integer bookedSlots(@Param("poolId") long poolId);
}
