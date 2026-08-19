package com.flashpilot.clinic.reconcile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashpilot.clinic.reconcile.ReconcileDecider.Action;
import com.flashpilot.clinic.reconcile.ReconcileDecider.Decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账判据的测试。
 *
 * <p>这个类要守的不变量只有一句：<b>凡是「不该动手」的情况，一次都不能动手。</b>
 * 因为补偿会直接改号源账目，误补一次就是凭空造号 = 自己制造超卖，
 * 而超卖对患者是直接违约、不可回退。
 *
 * <p>所以下面的用例里，<b>断言 acts() 为 false 的比断言 true 的多得多</b>，
 * 这个比例是刻意的：自动化的价值不在于它敢做多少，而在于它不敢做什么。
 */
class ReconcileDeciderTest {

    private static final int THRESHOLD = 3;
    private static final int CAP = 100;

    private static Decision decide(int vanished, boolean stable, int lastV, int consec) {
        return ReconcileDecider.decide(vanished, stable, lastV, consec, THRESHOLD, CAP, false);
    }

    // ---------- 闸门① 方向不对称 ----------

    @Test
    @DisplayName("负残差（潜在超卖）永远不自动处置，无论已经连续多少次")
    void neverAutoFixOversold() {
        // 关键：即使连续观测了很多次、即使量很小，也不能动手。
        // 回收号源等于取消一个真实患者的预约 —— 不可逆的方向必须留给人。
        for (int consec = 0; consec < THRESHOLD * 5; consec++) {
            Decision d = decide(-1, true, -1, consec);
            assertFalse(d.acts(), "负残差在连续 " + consec + " 次时被自动处置了");
            assertEquals(Action.REFUSE_OVERSOLD, d.action());
        }
        assertFalse(decide(-500, true, -500, 99).acts(), "大额负残差也不能自动处置");
    }

    @Test
    @DisplayName("负残差会把连续计数清零，避免它和之后的正残差混着数")
    void oversoldResetsCounter() {
        Decision d = decide(-3, true, 7, 2);
        assertEquals(0, d.nextConsecutive());
    }

    // ---------- 闸门② 采样必须稳定 ----------

    @Test
    @DisplayName("采样不稳定时不判定，而且【不推进】连续计数")
    void unstableSampleDoesNotCount() {
        // 这是最容易写错的一条：只要这里推进了计数，
        // 一个反复出现的采样偏移就能凑够次数触发补偿 —— 而那个残差本来不存在。
        Decision d = ReconcileDecider.decide(5, false, 5, 2, THRESHOLD, CAP, false);
        assertFalse(d.acts());
        assertEquals(Action.SKIP_UNSTABLE, d.action());
        assertEquals(2, d.nextConsecutive(), "不稳定采样推进了连续计数");
        assertEquals(5, d.nextLastVanished(), "不稳定采样改动了上次残差");
    }

    @Test
    @DisplayName("反复不稳定采样永远无法凑够次数触发补偿")
    void repeatedUnstableNeverFires() {
        int lastV = 0;
        int consec = 0;
        for (int i = 0; i < 50; i++) {
            Decision d = ReconcileDecider.decide(5, false, lastV, consec, THRESHOLD, CAP, false);
            assertFalse(d.acts(), "第 " + i + " 轮不稳定采样触发了补偿");
            lastV = d.nextLastVanished();
            consec = d.nextConsecutive();
        }
    }

    // ---------- 闸门③ 连续多次同一个数 ----------

    @Test
    @DisplayName("残差稳定复现到阈值才补偿，早一次都不行")
    void firesOnlyAtThreshold() {
        int lastV = 0;
        int consec = 0;
        for (int round = 1; round <= THRESHOLD; round++) {
            Decision d = decide(7, true, lastV, consec);
            if (round < THRESHOLD) {
                assertFalse(d.acts(), "第 " + round + " 轮就补偿了，阈值是 " + THRESHOLD);
                assertEquals(Action.OBSERVING, d.action());
            } else {
                assertTrue(d.acts(), "连续 " + THRESHOLD + " 次仍未补偿");
                assertEquals(7, d.amount());
            }
            lastV = d.nextLastVanished();
            consec = d.nextConsecutive();
        }
    }

    @Test
    @DisplayName("残差数值跳变则重新数，不能靠「都非零」凑够次数")
    void changingResidualRestartsCount() {
        // 判据是「连续同一个数」而不是「连续都非零」：数字还在变说明系统还在动
        // （消费在途、租约即将被回收），此时补偿会补到一个中间态上。
        int lastV = 0;
        int consec = 0;
        int[] jumping = {5, 6, 7, 8, 9, 10};
        for (int v : jumping) {
            Decision d = decide(v, true, lastV, consec);
            assertFalse(d.acts(), "残差一直在变（" + v + "）却触发了补偿");
            assertEquals(1, d.nextConsecutive(), "残差跳变后连续计数没有重置");
            lastV = d.nextLastVanished();
            consec = d.nextConsecutive();
        }
    }

    @Test
    @DisplayName("残差归零会清空状态，之后同样的残差要重新数满")
    void balancedClearsState() {
        Decision zero = decide(0, true, 7, 2);
        assertEquals(Action.BALANCED, zero.action());
        assertEquals(0, zero.nextConsecutive());
        assertEquals(0, zero.nextLastVanished());

        // 清零之后再出现 7，必须重新从 1 开始数
        Decision again = decide(7, true, zero.nextLastVanished(), zero.nextConsecutive());
        assertFalse(again.acts(), "归零后残差重现，第一次就补偿了");
        assertEquals(1, again.nextConsecutive());
    }

    // ---------- 闸门④ 单次上限 ----------

    @Test
    @DisplayName("超过单次上限只告警不动手，即使稳定复现")
    void refusesTooLarge() {
        int lastV = CAP + 1;
        int consec = THRESHOLD;   // 已经数满了
        Decision d = decide(CAP + 1, true, lastV, consec);
        assertFalse(d.acts(), "超上限的残差被自动补偿了");
        assertEquals(Action.REFUSE_TOO_LARGE, d.action());
    }

    @Test
    @DisplayName("恰好等于上限可以补，上限是闭区间")
    void capIsInclusive() {
        Decision d = decide(CAP, true, CAP, THRESHOLD - 1);
        assertTrue(d.acts(), "恰好等于上限时拒绝了，边界判断写成了 >=");
        assertEquals(CAP, d.amount());
    }

    @Test
    @DisplayName("超上限时保留连续计数，避免反复从 1 开始数、反复打同一条告警")
    void tooLargeKeepsCounter() {
        Decision d = decide(CAP + 50, true, CAP + 50, THRESHOLD);
        assertEquals(THRESHOLD + 1, d.nextConsecutive());
    }

    // ---------- dry-run ----------

    @Test
    @DisplayName("dry-run 不动手，也不消耗连续次数")
    void dryRunIsSideEffectFree() {
        // 预演如果推进了状态，「运维预演一次」就会消耗掉真实执行所需的连续次数，
        // 导致下一轮真的执行时反而不够数 —— 预演改变了被预演的东西。
        Decision d = ReconcileDecider.decide(7, true, 7, THRESHOLD - 1, THRESHOLD, CAP, true);
        assertFalse(d.acts());
        assertEquals(Action.DRY_RUN, d.action());
        assertEquals(7, d.amount(), "dry-run 应报出会补多少");
        assertEquals(THRESHOLD - 1, d.nextConsecutive(), "dry-run 推进了连续计数");
        assertEquals(7, d.nextLastVanished());
    }

    @Test
    @DisplayName("dry-run 同样遵守方向和上限两道闸门")
    void dryRunRespectsHardGates() {
        assertEquals(Action.REFUSE_OVERSOLD,
                ReconcileDecider.decide(-5, true, -5, 9, THRESHOLD, CAP, true).action());
        assertEquals(Action.REFUSE_TOO_LARGE,
                ReconcileDecider.decide(CAP + 1, true, CAP + 1, 9, THRESHOLD, CAP, true).action());
        assertEquals(Action.SKIP_UNSTABLE,
                ReconcileDecider.decide(5, false, 5, 9, THRESHOLD, CAP, true).action());
    }

    // ---------- 闸门交互：这里才是真正容易出事的地方 ----------

    @Test
    @DisplayName("稳定与不稳定交替出现时，只有稳定的那几次能累计")
    void interleavedStabilityCountsOnlyStable() {
        int lastV = 0;
        int consec = 0;
        boolean fired = false;
        // 交替：稳定、不稳定、稳定、不稳定…… 稳定的次数需要攒到 THRESHOLD
        for (int i = 0; i < THRESHOLD * 2 - 1; i++) {
            boolean stable = (i % 2 == 0);
            Decision d = ReconcileDecider.decide(7, stable, lastV, consec, THRESHOLD, CAP, false);
            if (d.acts()) {
                fired = true;
                // 第 0、2、4 轮是稳定的，第 4 轮（i=4）时稳定次数刚好到 3
                assertEquals(THRESHOLD * 2 - 2, i, "触发的轮次不对，说明不稳定采样也被计数了");
            }
            lastV = d.nextLastVanished();
            consec = d.nextConsecutive();
        }
        assertTrue(fired, "稳定次数攒满后仍未触发");
    }

    @Test
    @DisplayName("补偿后状态清零，不会连续补两次")
    void doesNotCompensateTwiceInARow() {
        Decision first = decide(7, true, 7, THRESHOLD - 1);
        assertTrue(first.acts());
        assertEquals(0, first.nextConsecutive(), "补偿后没有清零连续计数");
        assertEquals(0, first.nextLastVanished());

        // 假设补偿有 bug、残差没消失：下一轮必须重新数满才能再补，
        // 而不是立刻又补一次。否则自动化会每 30 秒补一次，把少卖变成持续超卖。
        Decision second = decide(7, true, first.nextLastVanished(), first.nextConsecutive());
        assertFalse(second.acts(), "补偿后立刻又补了第二次");
    }

    @Test
    @DisplayName("阈值为 1 时首次稳定观测即补偿（配置边界）")
    void thresholdOneFiresImmediately() {
        Decision d = ReconcileDecider.decide(7, true, 0, 0, 1, CAP, false);
        assertTrue(d.acts(), "阈值 1 时首次观测未触发");
    }

    @Test
    @DisplayName("残差为 0 且采样不稳定时，不当成「账目平衡」清零状态")
    void unstableZeroDoesNotClearState() {
        // 采样不稳定时读到 0 也不可信：可能正好在归还的中间态上读到了平衡。
        // 如果据此清零，之前攒的真实残差观测就被一次偶然的读数抹掉了。
        Decision d = ReconcileDecider.decide(0, false, 7, 2, THRESHOLD, CAP, false);
        assertEquals(Action.SKIP_UNSTABLE, d.action());
        assertEquals(2, d.nextConsecutive(), "不稳定采样读到 0 就清零了状态");
        assertEquals(7, d.nextLastVanished());
    }
}
