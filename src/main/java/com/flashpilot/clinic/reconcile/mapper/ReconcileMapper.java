package com.flashpilot.clinic.reconcile.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 对账留档。SQL 在 {@code resources/mapper/ReconcileMapper.xml}。
 *
 * <p>三类记录都要留：真动手、拒绝动手、以及关闭状态下的预演 ——
 * 「拒绝动手」的记录本身就是判据在起作用的证据。
 */
public interface ReconcileMapper {

    void insertLog(@Param("poolId") long poolId,
                   @Param("initialStock") int initialStock,
                   @Param("bucketSum") int bucketSum,
                   @Param("leaseHeld") int leaseHeld,
                   @Param("holdingAppts") int holdingAppts,
                   @Param("vanished") int vanished,
                   @Param("acted") boolean acted,
                   @Param("compensated") int compensated,
                   @Param("decision") String decision);

    List<Map<String, Object>> history(@Param("limit") int limit);
}
