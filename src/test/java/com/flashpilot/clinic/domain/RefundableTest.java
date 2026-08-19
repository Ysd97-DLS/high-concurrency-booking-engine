package com.flashpilot.clinic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 退号时限：距就诊不足 2 小时不许退。
 *
 * <p>这条规则的两个失败方向都有真实代价，但不对称：
 * <ul>
 *   <li><b>该退的退不了</b>——患者临时来不了却退不掉，号被白占，而他还付了钱。
 *       是运营投诉，但可人工处理。</li>
 *   <li><b>不该退的退了</b>——开诊前十分钟把号扔回池子。那个号<b>基本没人能用</b>：
 *       没人会在开诊前十分钟刷到并赶到医院。号源等于白白蒸发，
 *       而且它<b>会被等式③ 记成「已归还」——账目完全平衡，看不出任何问题</b>。</li>
 * </ul>
 * 后者更隐蔽，所以边界取严：<b>差一分钟不到 2 小时就不许退。</b>
 *
 * <p>另一件这批测试在守的事：{@code visitTime} 为 null 时的兜底。
 * 就诊时间是按序号推算的（{@code visitTimeOf}），理论上不该为 null，
 * 但真为 null 时代码用 8:00 兜底 —— 那是<b>当天最早的时刻</b>，
 * 也就是把退号窗口取到最窄。这个方向是对的：宁可少退，不可退了一个没人能用的号。
 */
class RefundableTest {

    private static final LocalDate VISIT_DAY = LocalDate.of(2026, 8, 25);
    private static final LocalTime VISIT_AT = LocalTime.of(10, 0);

    private static Appointment appt(ApptStatus status, LocalTime visitTime) {
        return new Appointment(
                1L, "A1001-1", 1001L, 5001L, 101L,
                VISIT_DAY, 1, visitTime,
                status, 5000,
                LocalDateTime.of(2026, 8, 24, 10, 0), null, null, "evt-1",
                LocalDateTime.of(2026, 8, 24, 9, 50));
    }

    /** 就诊时刻：2026-08-25 10:00。 */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 8, 25, hour, minute);
    }

    @Nested
    @DisplayName("两小时边界")
    class TwoHourBoundary {

        @Test
        @DisplayName("提前一天：能退")
        void dayBefore() {
            assertThat(appt(ApptStatus.BOOKED, VISIT_AT)
                    .refundable(LocalDateTime.of(2026, 8, 24, 10, 0))).isTrue();
        }

        @Test
        @DisplayName("提前 2 小时 1 分：能退")
        void justOverTwoHours() {
            assertThat(appt(ApptStatus.BOOKED, VISIT_AT).refundable(at(7, 59))).isTrue();
        }

        @Test
        @DisplayName("恰好提前 2 小时：**不能**退 —— 边界取严")
        void exactlyTwoHours() {
            // now + 2h == visitAt，而判据是 isBefore（严格早于），所以不满足。
            // 取严的理由：退了一个开诊前刚够 2 小时的号，实际上很难被别人用上，
            // 而账目却完全平衡，看不出问题。
            assertThat(appt(ApptStatus.BOOKED, VISIT_AT).refundable(at(8, 0))).isFalse();
        }

        @Test
        @DisplayName("提前 1 小时 59 分：不能退")
        void justUnderTwoHours() {
            assertThat(appt(ApptStatus.BOOKED, VISIT_AT).refundable(at(8, 1))).isFalse();
        }

        @Test
        @DisplayName("就诊时刻之后：不能退")
        void afterVisitTime() {
            assertThat(appt(ApptStatus.BOOKED, VISIT_AT).refundable(at(11, 0))).isFalse();
        }
    }

    @Nested
    @DisplayName("状态过滤")
    class StatusFilter {

        @Test
        @DisplayName("只有 BOOKED 能退 —— 待支付的单不该走退号路径")
        void onlyBooked() {
            // PENDING_PAY 的号靠超时释放回池子，走退号会把两条归还路径混在一起。
            assertThat(appt(ApptStatus.PENDING_PAY, VISIT_AT)
                    .refundable(LocalDateTime.of(2026, 8, 24, 10, 0))).isFalse();
        }

        @Test
        @DisplayName("已退号 / 已就诊 / 失约 / 已过期都不能再退")
        void terminalStatuses() {
            LocalDateTime wellBefore = LocalDateTime.of(2026, 8, 24, 10, 0);
            for (ApptStatus st : new ApptStatus[] { ApptStatus.REFUNDED, ApptStatus.COMPLETED,
                    ApptStatus.NO_SHOW, ApptStatus.EXPIRED }) {
                assertThat(appt(st, VISIT_AT).refundable(wellBefore))
                        .as("状态 %s 不该允许退号 —— 重复归还就是超卖", st)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("就诊时间缺失时的兜底")
    class MissingVisitTime {

        @Test
        @DisplayName("visitTime 为 null 时按 8:00 算 —— 取当天最早，把退号窗口收到最窄")
        void nullVisitTimeUsesEarliest() {
            // 8:00 是兜底值。提前一天仍可退。
            assertThat(appt(ApptStatus.BOOKED, null)
                    .refundable(LocalDateTime.of(2026, 8, 24, 23, 0))).isTrue();
            // 但当天 6:01 就不行了（6:01 + 2h = 8:01 > 8:00）——
            // 而如果兜底取的是当天最晚时刻，这里会允许退，
            // 那就可能退掉一个已经开诊的号。方向必须是「宁可少退」。
            assertThat(appt(ApptStatus.BOOKED, null).refundable(at(6, 1))).isFalse();
        }
    }
}
