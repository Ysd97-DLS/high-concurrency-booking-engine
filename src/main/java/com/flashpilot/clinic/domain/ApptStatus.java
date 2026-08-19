package com.flashpilot.clinic.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 预约单状态机。状态转移规则集中在这里，不散落在 service 里。
 *
 * <p><b>最容易搞错的是「哪些状态占着号源」</b>，因为它同时决定三件事：
 * 一人一号的唯一约束是否生效、号源要不要归还、以及一致性等式③ 怎么配平。
 * 这三处必须用同一个判断，否则会出现「号还回了池子但唯一索引还挡着患者重约」这类矛盾。
 *
 * <pre>
 *   [抢号成功] → PENDING_PAY ──支付──→ BOOKED ──就诊──→ COMPLETED
 *                     │                  │
 *                     │超时               ├─退号─→ REFUNDED   （归还号源）
 *                     ↓                  └─过期未就诊─→ NO_SHOW（不归还，计违约）
 *                  EXPIRED（归还号源）
 * </pre>
 */
public enum ApptStatus {

    /** 抢号成功、等待支付。号源已被这张单预占。 */
    PENDING_PAY,

    /** 已支付，预约生效。 */
    BOOKED,

    /** 超时未支付，号源已归还号池。 */
    EXPIRED,

    /** 患者主动退号，号源已归还号池。 */
    REFUNDED,

    /** 已就诊完成。 */
    COMPLETED,

    /**
     * 失约：到就诊时段结束仍未就诊。
     *
     * <p><b>刻意不归还号源。</b>号源的价值是「某个时间点某位医生的接诊能力」，
     * 时间过了这个能力就消失了，还回池子也没有任何人能用它。
     * 这和电商「退货回库」的语义完全不同，是挂号这个垂类必须想清楚的一点。
     */
    NO_SHOW;

    /**
     * 占着号源的状态。
     *
     * <p>NO_SHOW 和 COMPLETED 也算「占着」——号确实被这个患者消耗掉了，
     * 只是一个用了、一个浪费了。它们同样不允许患者对同一医生同一天再约。
     */
    private static final Set<ApptStatus> HOLDING =
            EnumSet.of(PENDING_PAY, BOOKED, COMPLETED, NO_SHOW);

    /** 号源已经还回池子的终态。 */
    private static final Set<ApptStatus> RELEASED = EnumSet.of(EXPIRED, REFUNDED);

    /** 合法的后继状态。空集表示终态。 */
    public Set<ApptStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAY -> EnumSet.of(BOOKED, EXPIRED);
            case BOOKED -> EnumSet.of(COMPLETED, REFUNDED, NO_SHOW);
            case EXPIRED, REFUNDED, COMPLETED, NO_SHOW -> EnumSet.noneOf(ApptStatus.class);
        };
    }

    public boolean canTransitionTo(ApptStatus next) {
        return allowedNext().contains(next);
    }

    /** 是否占着号源。决定一人一号约束是否生效，也决定等式③ 怎么配平。 */
    public boolean holdsSlot() {
        return HOLDING.contains(this);
    }

    /** 号源是否已归还池子。 */
    public boolean slotReleased() {
        return RELEASED.contains(this);
    }

    /** 是否为终态。 */
    public boolean terminal() {
        return allowedNext().isEmpty();
    }

    public String label() {
        return switch (this) {
            case PENDING_PAY -> "待支付";
            case BOOKED -> "已预约";
            case EXPIRED -> "已失效";
            case REFUNDED -> "已退号";
            case COMPLETED -> "已就诊";
            case NO_SHOW -> "已失约";
        };
    }
}
