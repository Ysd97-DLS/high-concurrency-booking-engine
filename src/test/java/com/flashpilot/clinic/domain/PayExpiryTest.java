package com.flashpilot.clinic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 支付超时的判定边界。
 *
 * <p>这个判据决定「号源什么时候回池子」，而它出错的两个方向<b>严重程度完全不对称</b>：
 * <ul>
 *   <li><b>判早了</b>——号被从一个还在付款的患者手里收走，而钱可能已经付了。这是超卖的入口。</li>
 *   <li><b>判晚了</b>——号在池子外多待几秒。可接受，下一轮扫描还会扫到。</li>
 * </ul>
 * 所以边界一律取「宁可晚」：恰好等于时限时<b>不算</b>过期。
 *
 * <p>另一件这批测试在守的事：这个方法<b>以前没有任何调用者</b>。
 * 「这张单过期了吗」有两份实现——一份是这里的 Java，一份藏在
 * {@code findExpiredPending} 的 SQL 字符串 {@code pay_deadline < ?} 里，而只有 SQL 那份在跑。
 * 现在释放任务以这个方法为权威判据、SQL 只负责粗筛，所以它的边界必须被钉住。
 */
class PayExpiryTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 8, 19, 10, 0, 0);

    private static Appointment appt(ApptStatus status, LocalDateTime payDeadline) {
        return new Appointment(
                1L, "A1001-1", 1001L, 5001L, 101L,
                LocalDate.of(2026, 8, 20), 1, LocalTime.of(9, 0),
                status, 5000, payDeadline, null, null, "evt-1",
                LocalDateTime.of(2026, 8, 19, 9, 50, 0));
    }

    @Nested
    @DisplayName("时间边界")
    class TimeBoundary {

        @Test
        @DisplayName("还没到时限：没过期")
        void beforeDeadline() {
            assertThat(appt(ApptStatus.PENDING_PAY, DEADLINE).payExpired(DEADLINE.minusSeconds(1)))
                    .isFalse();
        }

        @Test
        @DisplayName("恰好等于时限：**不算**过期 —— 判早了会从还在付款的患者手里收走号")
        void exactlyAtDeadline() {
            assertThat(appt(ApptStatus.PENDING_PAY, DEADLINE).payExpired(DEADLINE))
                    .isFalse();
        }

        @Test
        @DisplayName("超过时限 1 秒：过期")
        void oneSecondPast() {
            assertThat(appt(ApptStatus.PENDING_PAY, DEADLINE).payExpired(DEADLINE.plusSeconds(1)))
                    .isTrue();
        }

        @Test
        @DisplayName("和 SQL 粗筛的语义必须等价：`pay_deadline < now` ⟺ `now.isAfter(payDeadline)`")
        void agreesWithSqlSemantics() {
            // SQL 是 `pay_deadline < ?`，即严格小于 now；Java 是 now 严格大于 deadline。
            // 两者在同一个时刻上必须给同一个答案，否则那批单会被每轮扫出又跳过，
            // 占着单批上限把真正该释放的单挡在外面。
            for (long offsetSeconds : new long[] { -60, -1, 0, 1, 60 }) {
                LocalDateTime now = DEADLINE.plusSeconds(offsetSeconds);
                boolean sqlWouldMatch = DEADLINE.isBefore(now);          // pay_deadline < now
                boolean javaSays = appt(ApptStatus.PENDING_PAY, DEADLINE).payExpired(now);
                assertThat(javaSays)
                        .as("offset %d 秒时两个判据应一致", offsetSeconds)
                        .isEqualTo(sqlWouldMatch);
            }
        }
    }

    @Nested
    @DisplayName("状态过滤")
    class StatusFilter {

        @Test
        @DisplayName("已支付的单不会被超时释放 —— 否则就是把付过钱的号收走")
        void bookedNeverExpires() {
            assertThat(appt(ApptStatus.BOOKED, DEADLINE).payExpired(DEADLINE.plusHours(10)))
                    .isFalse();
        }

        @Test
        @DisplayName("已经是 EXPIRED 的单不会被再处理一次 —— 重复归还就是超卖")
        void expiredNotProcessedTwice() {
            assertThat(appt(ApptStatus.EXPIRED, DEADLINE).payExpired(DEADLINE.plusHours(10)))
                    .isFalse();
        }

        @Test
        @DisplayName("已退号、已就诊、失约的单都不参与超时释放")
        void otherTerminalStatuses() {
            for (ApptStatus st : new ApptStatus[] {
                    ApptStatus.REFUNDED, ApptStatus.COMPLETED, ApptStatus.NO_SHOW }) {
                assertThat(appt(st, DEADLINE).payExpired(DEADLINE.plusHours(10)))
                        .as("状态 %s 不应被判为支付超时", st)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("脏数据")
    class Malformed {

        @Test
        @DisplayName("时限为 null 时不算过期 —— 宁可漏释放，也不能凭一个空值收走号")
        void nullDeadlineIsNotExpired() {
            assertThat(appt(ApptStatus.PENDING_PAY, null).payExpired(DEADLINE.plusHours(10)))
                    .isFalse();
            // 注意这条和 SQL 是天然一致的：`pay_deadline < ?` 对 NULL 求值为 NULL，
            // WHERE 里当假处理，所以这种单根本不会被扫出来。两处对 NULL 的处理必须都是「不过期」。
        }
    }
}
