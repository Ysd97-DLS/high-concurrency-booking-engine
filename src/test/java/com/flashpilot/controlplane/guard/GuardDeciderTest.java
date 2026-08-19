package com.flashpilot.controlplane.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.guard.GuardDecider.Decision;
import com.flashpilot.controlplane.guard.GuardDecider.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 护栏判据的测试。
 *
 * <p>护栏是整个系统里<b>唯一允许 LLM 改动生产参数</b>的地方。项目的核心命题
 * 「把 LLM 放在秒级控制面而不是毫秒级请求路径」能否成立，取决于它是否真的挡得住。
 * 数据面没有 LLM 是靠架构保证的，控制面的安全是靠这个类保证的。
 *
 * <p>所以这里的用例分两类，缺一类都不行：
 * <ul>
 *   <li><b>该挡的必须挡住</b>——不在白名单、越界、震荡幅度、冷却期内；</li>
 *   <li><b>不该挡的绝不能挡</b>——人的明确指令。因为「过度保守」的护栏和「完全失效」的护栏
 *       都会让操作者失去对系统的控制，而前者更隐蔽：它返回 accepted=true。</li>
 * </ul>
 */
class GuardDeciderTest {

    private static final long COOLDOWN = 5_000L;
    private static final double RATIO = 0.5;
    private static final long LONG_AGO = 999_999L;

    /** 自动来源（L0/L1），冷却期早已过。 */
    private static Decision auto(String key, double requested, double current) {
        return GuardDecider.decide(key, requested, current, false, LONG_AGO, COOLDOWN, RATIO, false);
    }

    /** 人工来源。 */
    private static Decision manual(String key, double requested, double current) {
        return GuardDecider.decide(key, requested, current, true, 0L, COOLDOWN, RATIO, false);
    }

    // ---------- 白名单：最强的一道边界 ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "reconcile.enabled",          // 对账开关：刻意不在白名单，Agent 不该能开它
            "flashpilot.stock.leaseTtlMs",
            "limit.qps ",                 // 尾随空格
            "LIMIT_QPS",                  // 枚举名而不是键名
            "",
            "'; DROP TABLE t_appointment; --"
    })
    @DisplayName("不在白名单的键名一律驳回，连取值都不讨论")
    void rejectsNonWhitelisted(String key) {
        Decision d = auto(key, 100, 50);
        assertEquals(Verdict.NOT_WHITELISTED, d.verdict(), "键名 [" + key + "] 被放行了");
        assertFalse(d.wouldApply());
    }

    @Test
    @DisplayName("null 键名不抛异常，按驳回处理")
    void nullKeyIsRejected() {
        assertEquals(Verdict.NOT_WHITELISTED, auto(null, 1, 1).verdict());
    }

    @ParameterizedTest
    @EnumSource(ConfigParam.class)
    @DisplayName("白名单里的每一个参数都能被正常改动（不能有「登记了但调不动」的）")
    void everyWhitelistedParamIsActuallyTunable(ConfigParam p) {
        // 从量程下限出发，请求上限。人工来源排除限幅与冷却干扰，
        // 只验证「这个参数在护栏眼里是可改的」。
        Decision d = manual(p.key(), p.max(), p.min());
        assertTrue(d.applies(), p.key() + " 无法从下限改到上限");
        assertEquals(p.max(), d.target(), 1e-9);
    }

    // ---------- 取值合法性 ----------

    @Test
    @DisplayName("NaN / Inf 驳回，不能让它污染配置")
    void rejectsNonFinite() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Decision d = auto("limit.qps", bad, 1000);
            assertEquals(Verdict.INVALID_VALUE, d.verdict(), "非有限值 " + bad + " 被放行");
        }
    }

    @Test
    @DisplayName("非法值的回显是【请求值】而不是当前值——审计要记录尝试了什么")
    void invalidValueEchoesTheAttempt() {
        // 回显当前值会让审计出现「500 → 500」，看起来像一次无害的空变更，
        // 而真相是有人试图设成 NaN。把非法尝试显示成空操作，审计就失去了意义。
        Decision d = auto("riskcontrol.threshold", Double.NaN, 500);
        assertEquals(Verdict.INVALID_VALUE, d.verdict());
        assertTrue(Double.isNaN(d.target()), "回显的是当前值 " + d.target() + "，看不出尝试了 NaN");
    }

    // ---------- 区间钳制 ----------

    @Test
    @DisplayName("越界钳制到边界，并且必须在 note 里说出来")
    void clampsAndExplains() {
        Decision hi = manual("riskcontrol.threshold", 9999, 3);
        assertEquals(500, hi.target(), 1e-9);
        assertTrue(hi.note().contains("钳制"), "钳制了却没告诉操作者：" + hi.note());

        Decision lo = manual("riskcontrol.threshold", -5, 100);
        assertEquals(3, lo.target(), 1e-9);
        assertTrue(lo.note().contains("钳制"));
    }

    @Test
    @DisplayName("人工可以绕过限幅和冷却，但【绝不能】绕过区间钳制")
    void manualCannotBypassClamp() {
        // 区间是参数本身的物理约束，不是对操作者的不信任。
        // 把活跃桶数设成 10000 不是「人的判断」，是笔误 —— 护栏该挡。
        Decision d = manual("stock.buckets", 10_000, 8);
        assertEquals(32, d.target(), 1e-9, "人工绕过了区间钳制");
        assertTrue(d.applies());
    }

    // ---------- 幅度限制 ----------

    @Test
    @DisplayName("自动来源单次变化受限于 max(量程 5%, |当前值|) × ratio")
    void autoIsMagnitudeLimited() {
        ConfigParam p = ConfigParam.LIMIT_QPS;
        Decision d = auto(p.key(), p.max(), 1000);

        // 写这条测试时我先按「当前值 × ratio = 500」算，期望 1500，结果实际是 5997.5。
        // 是我的期望错了：限幅下限是<b>量程的 5%</b>（bug ⑪ 的修复），
        // limit.qps 量程 199900 → 下限 9995，所以单步允许 9995 × 0.5 = 4997.5。
        // 当前值远小于量程时，起作用的是这个下限而不是比例项。
        //
        // 所以断言写成契约而不是硬编码的数字 —— 硬编码的数字会在改量程时莫名失败，
        // 而契约能说清「为什么是这个值」。
        double range = p.max() - p.min();
        double expectedStep = Math.max(Math.max(1.0, range * 0.05), 1000) * RATIO;
        assertEquals(1000 + expectedStep, d.target(), 1e-9);

        // 真正要守的不变量：一步到不了极值。这才是限幅存在的理由。
        assertTrue(d.target() < p.max(), "一步就冲到了上限，限幅没起作用");
        assertTrue(d.note().contains("幅度超限"), "限幅了却没说明：" + d.note());
    }

    @Test
    @DisplayName("当前值很大时，起作用的是比例项而不是量程下限")
    void proportionalTermDominatesAtLargeCurrent() {
        // 和上一条互补：两个分支都要覆盖，否则 max() 里的任一项写错都测不出来。
        Decision d = auto("limit.qps", 200_000, 100_000);
        assertEquals(150_000, d.target(), 1e-9);   // 100000 × 0.5 = 50000
    }

    @Test
    @DisplayName("人工来源完全不限幅——限幅是为了防控制器震荡，不是否决人的判断")
    void manualIsNotMagnitudeLimited() {
        Decision d = manual("limit.qps", 200_000, 1000);
        assertEquals(200_000, d.target(), 1e-9, "人工变更被限幅了");
        assertFalse(d.note().contains("幅度超限"));
    }

    /**
     * bug ⑪ 的回归测试。
     *
     * <p>原实现的限幅下限是硬编码的 {@code max(1.0, |current|)}：对取值范围 0..600 的
     * {@code release.spreadSeconds}，从 0 出发一步只能走 {@code 1.0 × 0.5 = 0.5}，
     * 要到 10 需要 20 次变更，而每次都被 5 秒冷却期挡着 ——
     * <b>这个参数实际上永远调不动</b>，而接口仍然返回 accepted=true 配一个被篡改的值。
     *
     * <p>这类缺陷最恶劣的地方是它<b>看起来是成功的</b>：调用方拿到 accepted=true，
     * 不逐字读 note 根本不知道自己的指令被改成了 1/20。
     */
    @Test
    @DisplayName("bug ⑪ 回归：按比例限幅必须能从 0 出发走出有意义的一步")
    void proportionalLimitCanLeaveZero() {
        Decision d = auto("release.spreadSeconds", 60, 0);
        // 量程 600 × 5% = 30 作为绝对底，× ratio 0.5 = 单步至少 15
        assertTrue(d.target() >= 15,
                "从 0 出发一步只能走到 " + d.target() + "，这个参数会永远调不动");
        // 而且不能一步就冲到请求值 —— 限幅仍然要生效，只是下限合理了
        assertTrue(d.target() <= 60);
    }

    @ParameterizedTest
    @EnumSource(ConfigParam.class)
    @DisplayName("任何参数从量程下限出发，自动来源都必须能走出「至少量程 1%」的一步")
    void noParamIsStuckAtItsFloor(ConfigParam p) {
        // 这是上一条测试的一般化：bug ⑪ 只在 release.spreadSeconds 上被发现，
        // 但根因（限幅下限与量纲不相称）对任何参数都成立。
        // 逐个参数断言，才能保证以后新增参数时不会重新引入同一个缺陷。
        double range = p.max() - p.min();
        Decision d = GuardDecider.decide(p.key(), p.max(), p.min(), false,
                LONG_AGO, COOLDOWN, RATIO, false);
        double step = Math.abs(d.target() - p.min());
        assertTrue(step >= range * 0.01,
                p.key() + " 从下限 " + p.min() + " 出发一步只能走 " + step
                        + "（量程 " + range + "），实际上调不动");
    }

    @Test
    @DisplayName("限幅之后仍然要过一次钳制，收敛值不能跑出区间")
    void limitedValueStaysInRange() {
        // 当前值贴着上限、请求继续往上：限幅算出的 current + delta 会超出 max，
        // 必须再钳一次。少了这一步就会写入一个越界值 —— 而越界值是钳制本该防住的。
        Decision d = auto("stock.buckets", 32, 30);
        assertTrue(d.target() <= 32, "限幅后越界了：" + d.target());
    }

    // ---------- 空变更 ----------

    @Test
    @DisplayName("与当前值相同判为空变更，不写入也不算驳回")
    void detectsNoOp() {
        Decision d = auto("limit.qps", 1000, 1000);
        assertEquals(Verdict.NO_OP, d.verdict());
        assertFalse(d.applies());
    }

    @Test
    @DisplayName("空变更在冷却期内也报空变更，不报冷却——什么都不改的提案不该消耗冷却配额")
    void noOpTakesPrecedenceOverCooldown() {
        // 顺序错了会有两个后果：审计里多一条误导性的「被驳回」，
        // 以及调用方以为「等冷却过了就能改」，而其实根本没有要改的东西。
        Decision d = GuardDecider.decide("limit.qps", 1000, 1000, false, 0L, COOLDOWN, RATIO, false);
        assertEquals(Verdict.NO_OP, d.verdict());
    }

    @Test
    @DisplayName("被限幅收敛后恰好等于当前值时，判为空变更而不是放行")
    void limitedToCurrentIsNoOp() {
        // 极小的请求变化经限幅后可能收敛回当前值。此时如果放行，
        // 就会写一条「值没变」的配置变更并刷新冷却期，白占掉一次调整机会。
        Decision d = auto("stock.segmentEnabled", 1, 1);
        assertEquals(Verdict.NO_OP, d.verdict());
    }

    // ---------- 冷却期 ----------

    @Test
    @DisplayName("自动来源在冷却期内驳回，并告知还需多久")
    void autoRespectsCooldown() {
        Decision d = GuardDecider.decide("limit.qps", 1500, 1000, false, 1_000L, COOLDOWN, RATIO, false);
        assertEquals(Verdict.COOLING_DOWN, d.verdict());
        assertTrue(d.note().contains("4000"), "没说明还需多久：" + d.note());
    }

    @Test
    @DisplayName("人工绕过冷却期——出事时人必须能立刻干预")
    void manualBypassesCooldown() {
        Decision d = GuardDecider.decide("limit.qps", 1500, 1000, true, 0L, COOLDOWN, RATIO, false);
        assertTrue(d.applies(), "人工变更被冷却期挡住了");
    }

    @Test
    @DisplayName("冷却期边界：刚好等于 cooldown 就放行")
    void cooldownBoundaryIsInclusive() {
        Decision d = GuardDecider.decide("limit.qps", 1500, 1000, false, COOLDOWN, COOLDOWN, RATIO, false);
        assertTrue(d.applies(), "边界判断写成了 <=");
    }

    // ---------- dry-run ----------

    @Test
    @DisplayName("dry-run 报出会改成多少，但不放行")
    void dryRunReportsWithoutApplying() {
        Decision d = GuardDecider.decide("limit.qps", 1400, 1000, true, 0L, COOLDOWN, RATIO, true);
        assertEquals(Verdict.DRY_RUN, d.verdict());
        assertFalse(d.applies(), "dry-run 竟然放行了");
        assertTrue(d.wouldApply(), "dry-run 应该算「校验通过」");
        assertEquals(1400, d.target(), 1e-9);
        assertTrue(d.note().contains("dry-run"));
    }

    @Test
    @DisplayName("dry-run 同样受白名单、钳制、限幅、冷却四道约束")
    void dryRunRespectsAllGates() {
        assertEquals(Verdict.NOT_WHITELISTED,
                GuardDecider.decide("nope", 1, 1, false, LONG_AGO, COOLDOWN, RATIO, true).verdict());
        assertEquals(Verdict.COOLING_DOWN,
                GuardDecider.decide("limit.qps", 1500, 1000, false, 0L, COOLDOWN, RATIO, true).verdict());
        // 预演也要如实反映钳制后的值，否则运营按预演结果决策就会被误导
        Decision clamped =
                GuardDecider.decide("stock.buckets", 999, 8, true, 0L, COOLDOWN, RATIO, true);
        assertEquals(32, clamped.target(), 1e-9);
        assertTrue(clamped.note().contains("钳制"));
    }

    // ---------- 交叉：钳制 + 限幅 同时发生 ----------

    @Test
    @DisplayName("钳制和限幅同时发生时，note 必须两件都说")
    void bothAdjustmentsAreExplained() {
        // 操作者/Agent 需要知道自己的提案被改了两次。只说一件事，
        // 另一件就成了「静默修改」——而静默修改配置是最难排查的一类问题。
        Decision d = auto("riskcontrol.threshold", 100_000, 10);
        assertTrue(d.note().contains("钳制"), "缺钳制说明：" + d.note());
        assertTrue(d.note().contains("幅度超限"), "缺限幅说明：" + d.note());
        assertNotEquals(100_000, d.target());
    }

    @Test
    @DisplayName("放行且无调整时 note 是 ok，不留空——空字符串会让审计看起来像丢了字段")
    void cleanApplyNotesOk() {
        Decision d = manual("limit.qps", 1400, 1000);
        assertEquals("ok", d.note());
    }
}
