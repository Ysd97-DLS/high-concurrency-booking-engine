package com.flashpilot.verify.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 一致性校验报告的留档与回查。SQL 在 {@code resources/mapper/ConsistencyMapper.xml}。
 */
public interface ConsistencyMapper {

    void insertReport(@Param("poolId") long poolId,
                      @Param("initialStock") int initialStock,
                      @Param("bucketSum") int bucketSum,
                      @Param("leaseHeld") int leaseHeld,
                      @Param("streamLen") int streamLen,
                      @Param("orderCount") int orderCount,
                      @Param("duplicate") int duplicate,
                      @Param("deadLetter") int deadLetter,
                      @Param("oversold") int oversold,
                      @Param("undersold") int undersold,
                      @Param("passed") boolean passed,
                      @Param("detail") String detail);

    /** 历史报告，用来对比多轮实验。 */
    List<Map<String, Object>> history(@Param("limit") int limit);
}
