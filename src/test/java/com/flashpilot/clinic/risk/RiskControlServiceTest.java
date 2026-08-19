package com.flashpilot.clinic.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 频次阈值自适应公式的测试。
 *
 * <p>只测 {@link RiskControlService#effectiveThreshold} 这一个纯函数，不起 Spring 容器——
 * 它是整个风控里最需要被钉住的一条公式，也是唯一一处「参数会在运行时被系统自己改掉」的地方。
 *
 * <p>为什么这条公式值得单独一个测试类：它同时要满足两个方向相反的要求，
 * 而<b>任何一个方向失守都不会报错，只会安静地变成错的行为</b>：
 * <ul>
 *   <li>抬得不够 → 高负载下普通患者被误判（就是 bug ⑳ 的后果，52 万请求被丢）；</li>
 *   <li>抬得过头 → 黄牛怎么刷都不触发（漏判，而漏判没有任何人会投诉）。</li>
 * </ul>
 */
class RiskControlServiceTest {

    private static final int DEVICE_PATIENT_LIMIT = 5;

    @Test
    @DisplayName("低负载：噪声底为 0 时用配置值，行为完全不变")
    void usesConfiguredThresholdWhenNoNoise() {
        // 这一条保证风控 A/B 对照实验的结论不会因为引入自适应而失效 ——
        // 对照实验只有 90 个请求，噪声底是 0。
        assertEquals(3.0, RiskControlService.effectiveThreshold(3.0, 0));
        assertEquals(500.0, RiskControlService.effectiveThreshold(500.0, 0));
    }

    @Test
    @DisplayName("高负载：噪声底接管阈值，把普通患者的波动挡在下面")
    void raisesThresholdAboveNoise() {
        // 实测场景：约 47 万事件 / width 65536 → 噪声底 7，
        // 而普通患者（真实 2 次）的修正后估计值是 4。
        long noiseFloor = 7;
        double threshold = RiskControlService.effectiveThreshold(3.0, noiseFloor);

        assertEquals(21.0, threshold, "3 倍安全系数：7 × 3 = 21");
        assertTrue(4 < threshold,
                "普通患者的估计值 4 必须落在阈值之下 —— 这正是第一版修复没做到的事");
    }

    @Test
    @DisplayName("抬升后黄牛仍然被抓 —— 自适应不能变成关掉风控")
    void scalperStillExceedsRaisedThreshold() {
        long noiseFloor = 7;
        double deviceThreshold =
                RiskControlService.effectiveThreshold(3.0, noiseFloor) * DEVICE_PATIENT_LIMIT;

        assertEquals(105.0, deviceThreshold);
        // 一机多号代抢 200 个患者，设备维度的估计值约 200
        assertTrue(200 > deviceThreshold,
                () -> "黄牛设备估计值 200 必须超过抬升后的设备阈值 " + deviceThreshold);
    }

    @Test
    @DisplayName("阈值只会被抬高，永不被压低")
    void neverLowersConfiguredThreshold() {
        // 运营刻意调高阈值（比如压测时设 500）不能被噪声底反向压低，
        // 否则「我明明放宽了它却更严了」——这种反直觉行为最难排查。
        for (long noise : new long[] {0, 1, 7, 50, 100}) {
            double eff = RiskControlService.effectiveThreshold(500.0, noise);
            assertTrue(eff >= 500.0,
                    () -> "噪声底 " + noise + " 时阈值被压到了 " + eff + "，不能低于配置值 500");
        }
    }

    @Test
    @DisplayName("阈值随噪声底单调不减 —— 负载越高门槛越高，不能出现回落")
    void monotonicInNoiseFloor() {
        double prev = -1;
        for (long noise = 0; noise <= 200; noise += 7) {
            final long n = noise;
            double eff = RiskControlService.effectiveThreshold(3.0, n);
            assertTrue(eff >= prev,
                    () -> "噪声底增加时阈值不能下降（noise=" + n + "）");
            prev = eff;
        }
    }

    /**
     * 这条是整个自适应设计的<b>核心不变量</b>：
     * 无论负载多高，判据都不会退化成「对所有人恒真」。
     *
     * <p>bug ⑳ 的本质就是这个不变量被破坏了——噪声底 67 而阈值 3，
     * 于是每一个请求的估计值都超阈值。有了这条，同类问题不可能再发生。
     */
    @Test
    @DisplayName("核心不变量：阈值永远高于噪声底，判据不会恒真")
    void thresholdAlwaysExceedsNoiseFloor() {
        for (long noise = 0; noise <= 10_000; noise = noise * 2 + 1) {
            final long n = noise;
            double eff = RiskControlService.effectiveThreshold(3.0, n);
            assertTrue(eff > n,
                    () -> "噪声底 " + n + " 时阈值是 " + eff
                            + "，必须严格大于噪声底。否则一个只请求一两次的正常患者"
                            + "也会因为别人的计数被判成高频 —— 这就是 bug ⑳。");
        }
    }
}
