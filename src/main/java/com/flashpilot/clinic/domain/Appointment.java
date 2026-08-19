package com.flashpilot.clinic.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约单。
 *
 * <p>{@code scheduleId} 就是引擎里的 {@code poolId}，{@code patientId} 就是 {@code holderId}——
 * 业务侧保留有业务含义的名字，进引擎时才转换成通用概念。
 * 这层翻译是刻意的：引擎不该知道自己在卖号，业务也不该被引擎的词汇污染。
 */
public record Appointment(
        long id,
        String apptNo,
        long scheduleId,
        long patientId,
        long doctorId,
        LocalDate visitDate,
        int seqNo,
        LocalTime visitTime,
        ApptStatus status,
        int feeCents,
        LocalDateTime payDeadline,
        LocalDateTime paidAt,
        LocalDateTime cancelledAt,
        String eventId,
        LocalDateTime createdAt
) {

    /** 是否已经超过支付时限。定时任务据此把它转成 EXPIRED 并归还号源。 */
    public boolean payExpired(LocalDateTime now) {
        return status == ApptStatus.PENDING_PAY && payDeadline != null && now.isAfter(payDeadline);
    }

    /**
     * 退号规则：距就诊时间的远近决定能不能退。
     *
     * <p>规则见 docs/DOMAIN.md §3.3。小于 2 小时不许退，是为了避免号源被临时占坑又甩手，
     * 那种情况下号还回池子也基本没人能用了——和 NO_SHOW 不归还是同一个道理。
     */
    public boolean refundable(LocalDateTime now) {
        if (status != ApptStatus.BOOKED) {
            return false;
        }
        LocalDateTime visitAt = visitDate.atTime(visitTime == null ? LocalTime.of(8, 0) : visitTime);
        return now.plusHours(2).isBefore(visitAt);
    }
}
