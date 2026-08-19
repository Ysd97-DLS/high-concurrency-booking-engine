package com.flashpilot.controlplane.guard;

import java.util.Optional;

import com.flashpilot.controlplane.config.ConfigParam;

/**
 * 护栏的<b>判据</b>，抽成纯函数。
 *
 * <h2>为什么这一处最值得有测试</h2>
 *
 * 护栏是整个系统里<b>唯一允许 LLM 改动生产参数</b>的地方。项目的核心命题是
 * 「把 LLM 放在秒级的控制面而不是毫秒级的请求路径上」，而这个命题能否成立，
 * 完全取决于护栏是否真的挡得住不该放行的提案。
 * <b>数据面没有 LLM 这件事是靠架构保证的，控制面的安全是靠这个类保证的。</b>
 *
 * <p>而它出过一个很典型的缺陷（bug ⑪）：按比例限幅时下限写成硬编码的
 * {@code max(1.0, |current|)}，对取值范围 0..600 的参数来说，从 0 出发一步只能走 0.5，
 * 要到 10 需要 20 次变更而每次都被冷却期挡着 —— <b>该参数实际上永远调不动</b>，
 * 而接口仍然返回 {@code accepted=true} 配一个被篡改的值。
 * 这个缺陷是手工调参时偶然发现的，如果当时有测试，它根本不会存在。
 *
 * <p>判据留在 {@code GuardRail.submit()} 里的话，测它要 mock 掉 Redis 支撑的
 * {@code HotConfigService} 和整套配置属性，于是实际上只会测两三条主路径 ——
 * 而护栏的价值恰恰在那些<b>边界和交叉</b>：人工能不能绕过限幅？绕过限幅之后还受不受钳制？
 * 空变更和冷却期哪个先判？<b>难测的代码不会被测，然后就会在没测过的那个组合上出事。</b>
 *
 * <p>所以这里没有任何 I/O：输入是几个数和一个来源标记，输出是一个决定。
 * {@code GuardRail} 只负责把 Redis 读写和审计接上去。
 */
public final class GuardDecider {

    /** 浮点比较容差。参数值都是「速率 / 个数 / 秒数」这类量纲，1e-6 足够。 */
    static final double NOOP_EPSILON = 1e-6;

    /**
     * 按比例限幅的<b>绝对下限相对参数量程的比例</b>。
     *
     * <p>这个 5% 是 bug ⑪ 的修复核心：限幅下限必须与参数自身量纲相称。
     * 硬编码 1.0 对 0..600 的参数太小（一步 0.5），对 0..1 的开关又太大（一步就能翻转）。
     * 取量程的 5% 则两者都合理：600 的量程一步至少能走 30，1 的量程一步 0.05。
     */
    static final double FLOOR_RANGE_RATIO = 0.05;

    private GuardDecider() {
    }

    /** 护栏可能做出的处置。 */
    public enum Verdict {
        /** 参数不在白名单里。<b>这是最强的一道边界</b>，连「改到什么值」都不必讨论。 */
        NOT_WHITELISTED,
        /** 取值是 NaN / Inf。 */
        INVALID_VALUE,
        /** 与当前值相同，不必变更。 */
        NO_OP,
        /** 冷却期未过（只对自动来源生效）。 */
        COOLING_DOWN,
        /** 预演：校验全过，会改成 target，但不写入。 */
        DRY_RUN,
        /** 放行。 */
        APPLY
    }

    /**
     * @param target 最终会写入的值（已过钳制与限幅）。被驳回时是「请求值或当前值」，仅供回显
     * @param note   护栏做了什么。<b>钳制和限幅都必须在这里说出来</b>——
     *               操作者输入 300 实际生效 20，界面不解释的话他会以为自己的指令生效了
     */
    public record Decision(
            Verdict verdict,
            ConfigParam param,
            double target,
            String note
    ) {
        public boolean applies() {
            return verdict == Verdict.APPLY;
        }

        /** 是否通过了全部校验（dry-run 也算通过，只是不写）。 */
        public boolean wouldApply() {
            return verdict == Verdict.APPLY || verdict == Verdict.DRY_RUN;
        }
    }

    /**
     * 判断一个提案该被怎么处置。
     *
     * @param paramKey            提案要改的参数键名（可能不在白名单里）
     * @param requested           提案值
     * @param current             该参数当前值
     * @param manual              是否人工来源。<b>人工绕过限幅与冷却，但不绕过白名单与钳制</b>
     * @param sinceLastChangeMs   距上次变更的毫秒数
     * @param cooldownMs          冷却期
     * @param maxChangeRatio      单次最大变化比例
     * @param dryRun              只校验不写入
     */
    public static Decision decide(String paramKey, double requested, double current,
                                  boolean manual, long sinceLastChangeMs, long cooldownMs,
                                  double maxChangeRatio, boolean dryRun) {

        // ① 白名单。
        //
        // 这是<b>比护栏其余部分更强的一道边界</b>：钳制管的是「改到什么值」，
        // 白名单管的是「能不能碰」。不在表里的键名连讨论取值的机会都没有 ——
        // 所以真正危险的能力（比如对账补偿的开关）就该靠不进白名单来隔离，
        // 而不是靠把区间设窄。
        Optional<ConfigParam> found = ConfigParam.byKey(paramKey);
        if (found.isEmpty()) {
            return new Decision(Verdict.NOT_WHITELISTED, null, requested, "参数不在白名单内，已驳回");
        }
        ConfigParam param = found.get();

        if (Double.isNaN(requested) || Double.isInfinite(requested)) {
            // 回显<b>请求值</b>而不是当前值。回显当前值会让审计里出现「500 → 500」，
            // 看起来像一次空变更，而真相是「有人试图把它设成 NaN」——
            // 审计的用途恰恰是记录尝试了什么，把非法尝试显示成无害的空操作就本末倒置了。
            return new Decision(Verdict.INVALID_VALUE, param, requested, "取值非法（NaN/Inf），已驳回");
        }

        StringBuilder note = new StringBuilder();

        // ② 区间钳制。人工也不能绕 —— 区间是参数本身的物理约束，
        // 不是对操作者的不信任。把活跃桶数设成 10000 不是「人的判断」，是笔误。
        double target = param.clamp(requested);
        if (Math.abs(target - requested) > NOOP_EPSILON) {
            note.append(String.format("越界已钳制 %s→%s；", fmt(requested), fmt(target)));
        }

        // ③ 幅度限制。<b>只对自动控制器生效。</b>
        //
        // 限幅的目的是防止 L0/L1 一步跳到极值造成震荡，而不是否决人的判断。
        // 运营明确输入 10 却生效 0.5，这不是安全，是无视操作者 ——
        // 而且它还返回 accepted=true，操作者不看 note 根本不知道指令被改了。
        // 冷却期已经因为同样的理由对人工放行，限幅没有理由不一致。
        if (!manual) {
            double range = param.max() - param.min();
            double floor = Math.max(1.0, range * FLOOR_RANGE_RATIO);
            double maxDelta = Math.max(floor, Math.abs(current)) * maxChangeRatio;
            if (Math.abs(target - current) > maxDelta) {
                double limited = param.clamp(current + Math.signum(target - current) * maxDelta);
                note.append(String.format("单次幅度超限已收敛 %s→%s；", fmt(target), fmt(limited)));
                target = limited;
            }
        }

        // ④ 空变更。放在冷却期<b>之前</b>：一个什么都不改的提案不该消耗冷却配额，
        // 也不该产生一条「被驳回」的审计噪声。
        if (Math.abs(target - current) <= Math.max(NOOP_EPSILON, Math.abs(current) * 1e-4)) {
            return new Decision(Verdict.NO_OP, param, current, "与当前值相同，无需变更");
        }

        // ⑤ 冷却期。人工绕过 —— 出事时人必须能立刻干预。
        if (!manual && sinceLastChangeMs < cooldownMs) {
            return new Decision(Verdict.COOLING_DOWN, param, target,
                    String.format("冷却期未过（还需 %d ms），已驳回", cooldownMs - sinceLastChangeMs));
        }

        if (dryRun) {
            note.append("dry-run 未写入");
            return new Decision(Verdict.DRY_RUN, param, target, note.toString());
        }
        return new Decision(Verdict.APPLY, param, target, note.isEmpty() ? "ok" : note.toString());
    }

    /** 数字格式化：整数不带小数点，其余保留一位。审计里全是这类数，统一口径便于比对。 */
    static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9
                ? String.valueOf((long) Math.rint(v))
                : String.format("%.1f", v);
    }
}
