package com.flashpilot.clinic.risk.mapper;

import java.util.List;

/**
 * 风控事件与失约黑名单。SQL 在 {@code resources/mapper/RiskEventMapper.xml}。
 */
public interface RiskEventMapper {

    /**
     * 一条风控命中记录。
     *
     * <p>原来这是个 {@code Object[]{patientId, deviceId, level, action, reason}} ——
     * 靠下标和 SQL 里的占位符顺序对齐，插错一个位置编译器完全看不出来。
     * 迁 MyBatis 时顺手换成 record：现在字段是有名字的，XML 里写的也是
     * {@code #{e.deviceId}} 而不是第几个问号。
     */
    record RiskEvent(long patientId, String deviceId, String level, String action, String reason) {
    }

    void insertBatch(@org.apache.ibatis.annotations.Param("batch") List<RiskEvent> batch);

    /** 当前仍在封禁期内的患者。黑名单是风控里唯一会「真正拒绝」的手段。 */
    List<Long> findBlockedPatients();
}
