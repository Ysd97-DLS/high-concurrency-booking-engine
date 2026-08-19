package com.flashpilot.clinic.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CountMinSketch 的回归测试。
 *
 * <p><b>这是这个项目里最该有的一组测试</b>，因为它守的是曾经造成最大破坏的那个缺陷：
 * CMS 的噪声底（窗口事件数 ÷ width）盖过了判据阈值，导致高负载下
 * <b>每一个请求都被判成高频</b>，风控从"识别黄牛"退化成"全局熔断器"。
 *
 * <p>那个缺陷之所以三小时没被发现，是因为它的外部表现只是"成交数偏低、误拒率偏高"——
 * 和"控制面把限流砍狠了"长得一模一样。<b>没有任何东西会报错。</b>
 * 所以它必须由测试来守，而不能靠下次再肉眼看出来。
 */
class CountMinSketchTest {

    private static final int DEPTH = 4;
    /** 生产配置。测试里刻意用真实值，否则测不到真实的冲突率。 */
    private static final int WIDTH = 65536;
    /** 生产默认的风控阈值。 */
    private static final int THRESHOLD = 3;

    @Test
    @DisplayName("width 必须是 2 的幂 —— 位运算取模的前提")
    void widthMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(4, 1000));
        // 2 的幂应当正常构造
        new CountMinSketch(4, 1024);
    }

    @Test
    @DisplayName("低负载下估计值精确 —— 风控 A/B 对照实验依赖这一点")
    void exactUnderLowLoad() {
        CountMinSketch cms = new CountMinSketch(DEPTH, WIDTH);
        // 对照实验的规模：50 个正常患者各请求 1 次
        for (int i = 1; i <= 50; i++) {
            assertEquals(1, cms.incrementAndEstimate("p:" + i),
                    "各请求一次的患者，估计值必须是 1");
        }
        assertEquals(0, cms.noiseFloor(), "50 个事件 / 65536 列，噪声底应为 0");
    }

    /**
     * <b>核心回归测试。</b>
     *
     * <p>复现生产缺陷的条件：约 55 万事件散布在 20 万个 key 上（压测画像就是
     * "20 万患者各请求两三次"），然后检查一个只出现 2 次的普通患者会不会被误判。
     *
     * <p>修复前：噪声底 ≈ 67（width=8192），这个患者的估计值约 69，远超阈值 3 → 误判。
     * 修复后：width 加大 8 倍 + 减掉噪声底，估计值回到 2 附近 → 正常放行。
     */
    /**
     * <b>核心回归测试，而且它当场推翻了我的第一版修复。</b>
     *
     * <p>我原本断言「高负载下普通患者的估计值 ≤ 阈值 3」，结果实测是 <b>4</b>——
     * 也就是说 width 加大 8 倍 + 减掉噪声底之后，<b>阈值 3 在高负载下依然会误判</b>。
     * 而我之前手工验证过的两个场景恰好都绕开了这个组合：
     * A/B 对照跑在低负载（噪声底 0），P1 压测用的阈值是 500。
     *
     * <p>数学上这是硬限制：减掉噪声底只消除了<b>均值</b>，残余波动仍在若干计数单位量级，
     * 而阈值 3 本身就在这个量级里。所以真正的修复是让阈值自适应噪声底，
     * 见 {@link RiskControlService#effectiveThreshold}。
     *
     * <p>这条测试现在守的是<b>草图层面能保证的性质</b>：修正后的估计值不会超出
     * 「真实计数 + 噪声底」这个上界，也就是修正确实把系统性偏差消掉了。
     * 「普通患者不被误判」这个<b>端到端</b>性质由 {@link RiskControlServiceTest} 守。
     */
    @Test
    @DisplayName("高负载下修正把系统性偏差消掉 —— 守住 bug ⑳")
    void correctionRemovesSystematicBiasUnderHighLoad() {
        CountMinSketch cms = new CountMinSketch(DEPTH, WIDTH);

        // 铺底噪声：20 万个不同患者，各 2~3 次，共约 47 万事件
        for (int i = 0; i < 200_000; i++) {
            cms.incrementAndEstimate("p:noise" + i);
            cms.incrementAndEstimate("p:noise" + i);
            if (i % 3 == 0) {
                cms.incrementAndEstimate("p:noise" + i);
            }
        }

        // 被观察的普通患者：只请求 2 次（正常患者的典型行为：刷新一下再点）
        cms.incrementAndEstimate("p:ordinary-patient");
        long estimate = cms.incrementAndEstimate("p:ordinary-patient");

        long noise = cms.noiseFloor();
        assertTrue(noise > 0, "这个规模下噪声底必须大于 0，否则本测试没有测到冲突场景");

        long trueCount = 2;
        assertTrue(estimate >= trueCount,
                () -> "CMS 不应低估到真实计数以下太多。估计值=" + estimate + " 真实=" + trueCount);
        assertTrue(estimate <= trueCount + noise,
                () -> "修正后的估计值必须落在「真实计数 + 噪声底」以内，说明系统性偏差已被消除。"
                        + "估计值=" + estimate + " 真实=" + trueCount + " 噪声底=" + noise);

        // 把「不修正会怎样」也断言出来：原始估计值确实远超真实计数。
        // 这一行证明本测试真的复现了缺陷条件，不是在低噪声场景里空转。
        long rawEstimate = estimate + noise;
        assertTrue(rawEstimate > trueCount * 3,
                () -> "未扣噪声底的原始估计值应当远超真实计数（实测约 " + rawEstimate
                        + " vs 真实 " + trueCount + "），否则说明没有复现出缺陷条件");
    }

    /**
     * 修复不能变成阉割：减掉噪声底之后，真正的高频行为仍然要能被认出来。
     *
     * <p>这条和上一条<b>必须成对存在</b>。只有上一条的话，
     * 把阈值调到无穷大也能通过测试，而那等于把风控关掉。
     */
    @Test
    @DisplayName("高负载下黄牛设备仍被识别 —— 修复不能变成阉割")
    void scalperStillDetectedUnderHighLoad() {
        CountMinSketch cms = new CountMinSketch(DEPTH, WIDTH);

        for (int i = 0; i < 200_000; i++) {
            cms.incrementAndEstimate("d:noise" + i);
            cms.incrementAndEstimate("d:noise" + i);
        }

        // 一机多号：同一设备代抢 200 个患者
        long last = 0;
        for (int i = 0; i < 200; i++) {
            last = cms.incrementAndEstimate("d:scalper-device");
        }
        // lambda 里要用，必须是有效 final
        final long estimate = last;

        int deviceThreshold = THRESHOLD * 5;    // RiskControlService.DEVICE_PATIENT_LIMIT
        assertTrue(estimate > deviceThreshold,
                () -> "黄牛设备 200 次请求必须超过设备阈值 " + deviceThreshold
                        + "，实际估计值=" + estimate);
        // 高估是允许的，低估到"看不见"才是问题。给一个宽松的下界防止修正过度。
        assertTrue(estimate >= 150,
                () -> "真实计数 200，估计值 " + estimate + " 低得过分，说明噪声底修正过度了");
    }

    @Test
    @DisplayName("噪声底 = 窗口事件总数 ÷ width")
    void noiseFloorFormula() {
        CountMinSketch cms = new CountMinSketch(DEPTH, 1024);
        for (int i = 0; i < 4096; i++) {
            cms.incrementAndEstimate("k" + i);
        }
        assertEquals(4096 / 1024, cms.noiseFloor());
    }

    /**
     * 轮转时事件总数必须和计数桶<b>同步</b>清。
     *
     * <p>如果只清桶不清总数，噪声底会虚高，估计值被减成 0 —— 判据永远不触发，
     * <b>漏掉真黄牛</b>。这个方向的失败比误判更难发现：
     * 误判会有人投诉，漏判是安静的。
     */
    @Test
    @DisplayName("轮转后事件总数与计数桶同步归零")
    void rotateResetsTotalsTogether() {
        CountMinSketch cms = new CountMinSketch(DEPTH, 1024);
        for (int i = 0; i < 8192; i++) {
            cms.incrementAndEstimate("k" + i);
        }
        long before = cms.noiseFloor();
        assertTrue(before > 0);

        cms.rotate();   // current → previous，两者都还在估计窗口内
        cms.rotate();   // 再转一次，最初那批彻底移出窗口

        assertEquals(0, cms.noiseFloor(),
                "两次轮转后旧事件应完全移出窗口，噪声底必须回到 0");
        assertEquals(1, cms.incrementAndEstimate("brand-new-key"),
                "窗口清空后新 key 的估计值应当精确");
    }

    @Test
    @DisplayName("滑动窗口跨两个桶：上一窗口的计数仍然算在内")
    void slidingWindowSpansTwoBuckets() {
        CountMinSketch cms = new CountMinSketch(DEPTH, 1024);
        cms.incrementAndEstimate("k");
        cms.incrementAndEstimate("k");
        cms.rotate();
        // 轮转后旧计数进 previous，仍应被 estimate 看到 —— 否则窗口边界会漏掉持续行为
        assertEquals(2, cms.estimate("k"));
        assertEquals(3, cms.incrementAndEstimate("k"));
    }

    @Test
    @DisplayName("估计值永不为负 —— 噪声底修正必须有下界")
    void estimateNeverNegative() {
        CountMinSketch cms = new CountMinSketch(DEPTH, 1024);
        // 造出噪声底远大于任一真实计数的局面
        for (int i = 0; i < 100_000; i++) {
            cms.incrementAndEstimate("k" + i);
        }
        assertTrue(cms.noiseFloor() > 1, "前置条件：噪声底应当远大于 1");
        assertTrue(cms.estimate("never-seen-key") >= 0, "估计值不能是负数");
        assertTrue(cms.estimate("k1") >= 0, "估计值不能是负数");
    }

    @Test
    @DisplayName("内存固定，不随 key 数量增长 —— 选 CMS 的全部理由")
    void memoryIsFixed() {
        CountMinSketch cms = new CountMinSketch(DEPTH, WIDTH);
        int empty = cms.memoryBytes();
        for (int i = 0; i < 500_000; i++) {
            cms.incrementAndEstimate("k" + i);
        }
        assertEquals(empty, cms.memoryBytes(),
                "灌入 50 万 key 后内存占用必须不变，这是 CMS 相对 HashMap 的核心优势");
        assertEquals(DEPTH * WIDTH * Long.BYTES * 2, empty);
    }
}
