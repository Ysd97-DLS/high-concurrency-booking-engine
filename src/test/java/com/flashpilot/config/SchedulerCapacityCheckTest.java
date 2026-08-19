package com.flashpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashpilot.config.SchedulerCapacityCheck.Verdict;

/**
 * 调度池容量判定。
 *
 * <p>这个检查器守的是「池子线程数 &gt;= 定时任务数」这条不变量，
 * 而那条不变量原来只写在 yml 的注释里、附一句人工计数的「当前 N 个」——
 * 那个数字<b>漂移过两次</b>，其中一次配置值恰好等于旧计数，
 * 也就是说<b>不变量当时其实已经被违反了</b>，只是负载不高没被饿到。
 *
 * <p>池子不够的后果是<b>定时任务被静默饿死：不报错、不告警、日志里什么都没有</b>。
 * 所以这个检查器自己绝不能出错 —— 它是唯一会喊的那个东西。
 */
class SchedulerCapacityCheckTest {

    private static final int HEADROOM = 2;

    @Test
    @DisplayName("池子比任务少：判 INSUFFICIENT")
    void fewerThreadsThanTasks() {
        assertThat(SchedulerCapacityCheck.judge(15, 14, HEADROOM)).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(SchedulerCapacityCheck.judge(15, 1, HEADROOM)).isEqualTo(Verdict.INSUFFICIENT);
    }

    @Test
    @DisplayName("恰好相等：判 THIN 而**不是** OK —— 相等不是安全状态")
    void exactlyEqualIsNotSafe() {
        // 所有任务同时到点时，一个慢任务就会让其它任务排队等待。
        // 而这些任务里有 1 秒级的 AIMD 控制器和指标采集 —— 它们迟到就等于控制面失灵。
        assertThat(SchedulerCapacityCheck.judge(15, 15, HEADROOM)).isEqualTo(Verdict.THIN);
    }

    @Test
    @DisplayName("余量差 1（小于要求的 2）：仍判 THIN")
    void oneShortOfHeadroom() {
        assertThat(SchedulerCapacityCheck.judge(15, 16, HEADROOM)).isEqualTo(Verdict.THIN);
    }

    @Test
    @DisplayName("余量刚好达到要求：判 OK")
    void exactlyAtHeadroom() {
        assertThat(SchedulerCapacityCheck.judge(15, 17, HEADROOM)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("余量充足：判 OK —— 这是项目当前的配置（15 个任务 / 18 个线程）")
    void currentProjectConfig() {
        assertThat(SchedulerCapacityCheck.judge(15, 18, HEADROOM)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("Spring Boot 的默认单线程调度器：任何多任务场景都判 INSUFFICIENT")
    void springDefaultSingleThread() {
        // 读不到配置时的兜底值是 1，也就是默认调度器。
        // 那比「配小了」更危险：一个任务卡住会把其它全部拖死。
        assertThat(SchedulerCapacityCheck.judge(2, 1, HEADROOM)).isEqualTo(Verdict.INSUFFICIENT);
    }

    @Test
    @DisplayName("边界：零任务时不该误报")
    void noTasks() {
        // 没有定时任务时池子多大都无所谓，不该打扰运维。
        assertThat(SchedulerCapacityCheck.judge(0, 1, HEADROOM)).isEqualTo(Verdict.OK);
        assertThat(SchedulerCapacityCheck.judge(0, 18, HEADROOM)).isEqualTo(Verdict.OK);
    }
}
