package com.flashpilot.clinic.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 预约单状态机的测试。
 *
 * <p>这里最重要的不是「PENDING_PAY 能不能到 BOOKED」这类显而易见的转移，
 * 而是<b>「占号 / 已归还」这个划分必须覆盖全部状态且互不重叠</b>。
 * 因为一致性等式③ 的配平公式是
 * <pre>总号数 == 桶剩余 + 实例持有 + <b>占号预约数</b> + 已发出未落库</pre>
 * 「占号预约数」直接取自 {@link ApptStatus#holdsSlot()}。
 *
 * <p>所以只要有人加了第七种状态而忘了归类，等式③ 就会开始报残差，
 * 而<b>报出来的现象是"号源凭空消失"，根本不会指向状态机</b>——
 * 排查方向会完全错。这条测试就是为了让那个错误在编译后立刻被抓住，
 * 而不是等到某次压测报出一个莫名其妙的残差。
 */
class ApptStatusTest {

    @Test
    @DisplayName("占号 / 已归还 必须覆盖全部状态，且互不重叠")
    void holdingAndReleasedPartitionAllStates() {
        for (ApptStatus s : ApptStatus.values()) {
            assertTrue(s.holdsSlot() ^ s.slotReleased(),
                    () -> "状态 " + s + " 必须恰好属于「占号」或「已归还」之一。"
                            + "两者都不属于会让等式③ 少算这部分号源（表现为号源凭空消失）；"
                            + "两者都属于会重复计数。新增状态时必须同时归类。");
        }
    }

    @Test
    @DisplayName("NO_SHOW 占号而不归还 —— 挂号垂类最关键的一条业务判断")
    void noShowHoldsSlotAndIsNotReleased() {
        // 号源的价值是「某时间点某医生的接诊能力」，时间过了这个能力就消失了，
        // 还回池子也没人能用。这和电商「退货回库」语义完全不同。
        assertTrue(ApptStatus.NO_SHOW.holdsSlot(),
                "失约的号已经被消耗掉了（只是浪费了），不能算回可用号源");
        assertFalse(ApptStatus.NO_SHOW.slotReleased(),
                "失约不归还号源 —— 如果归还，等式③ 会把一个已经消失的号算进可用");
    }

    @Test
    @DisplayName("COMPLETED 也占号 —— 用掉和浪费掉都是消耗")
    void completedHoldsSlot() {
        assertTrue(ApptStatus.COMPLETED.holdsSlot());
        assertFalse(ApptStatus.COMPLETED.slotReleased());
    }

    @Test
    @DisplayName("只有 EXPIRED 和 REFUNDED 归还号源")
    void onlyExpiredAndRefundedRelease() {
        assertEquals(EnumSet.of(ApptStatus.EXPIRED, ApptStatus.REFUNDED),
                EnumSet.allOf(ApptStatus.class).stream()
                        .filter(ApptStatus::slotReleased)
                        .collect(() -> EnumSet.noneOf(ApptStatus.class), EnumSet::add, EnumSet::addAll),
                "归还号源的状态集合变了就必须同步检查 AppointmentService.releaseOne 的调用点");
    }

    @Test
    @DisplayName("待支付只能走向已预约或已失效")
    void pendingPayTransitions() {
        assertEquals(EnumSet.of(ApptStatus.BOOKED, ApptStatus.EXPIRED),
                ApptStatus.PENDING_PAY.allowedNext());
        assertTrue(ApptStatus.PENDING_PAY.canTransitionTo(ApptStatus.BOOKED));
        assertTrue(ApptStatus.PENDING_PAY.canTransitionTo(ApptStatus.EXPIRED));
        // 不能跳过支付直接就诊
        assertFalse(ApptStatus.PENDING_PAY.canTransitionTo(ApptStatus.COMPLETED));
        assertFalse(ApptStatus.PENDING_PAY.canTransitionTo(ApptStatus.NO_SHOW));
    }

    @Test
    @DisplayName("已预约可以就诊 / 退号 / 失约")
    void bookedTransitions() {
        assertEquals(EnumSet.of(ApptStatus.COMPLETED, ApptStatus.REFUNDED, ApptStatus.NO_SHOW),
                ApptStatus.BOOKED.allowedNext());
        assertFalse(ApptStatus.BOOKED.canTransitionTo(ApptStatus.EXPIRED),
                "已支付的单不该再走超时失效这条路 —— 超时释放只针对 PENDING_PAY");
        assertFalse(ApptStatus.BOOKED.canTransitionTo(ApptStatus.PENDING_PAY),
                "不能退回待支付");
    }

    /**
     * 终态不可复活。
     *
     * <p>这条守的是一类很实际的风险：退号之后号源已经还回池子了，
     * 如果这张单还能被改回 BOOKED，<b>同一个号就被卖了两次</b>。
     */
    @ParameterizedTest
    @EnumSource(names = {"EXPIRED", "REFUNDED", "COMPLETED", "NO_SHOW"})
    @DisplayName("终态不可复活 —— 否则已归还的号会被二次占用")
    void terminalStatesCannotTransition(ApptStatus terminal) {
        assertTrue(terminal.terminal(), terminal + " 应当是终态");
        assertTrue(terminal.allowedNext().isEmpty());
        for (ApptStatus any : ApptStatus.values()) {
            assertFalse(terminal.canTransitionTo(any),
                    () -> terminal + " 不该能转移到 " + any
                            + "。号源已归还的单若能改回占号状态，等于把同一个号卖两次。");
        }
    }

    @ParameterizedTest
    @EnumSource
    @DisplayName("每个状态都有中文标签 —— 前端直接展示，缺一个就是空白")
    void everyStatusHasLabel(ApptStatus s) {
        assertNotNull(s.label());
        assertFalse(s.label().isBlank(), s + " 缺少中文标签");
    }

    @Test
    @DisplayName("非终态恰好是 PENDING_PAY 和 BOOKED")
    void nonTerminalStates() {
        assertFalse(ApptStatus.PENDING_PAY.terminal());
        assertFalse(ApptStatus.BOOKED.terminal());
        long nonTerminal = EnumSet.allOf(ApptStatus.class).stream()
                .filter(s -> !s.terminal()).count();
        assertEquals(2, nonTerminal, "只有待支付和已预约是活动状态，其余都是终态");
    }
}
