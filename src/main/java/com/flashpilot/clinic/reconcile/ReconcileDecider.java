package com.flashpilot.clinic.reconcile;

/**
 * 对账补偿的<b>判据</b>，抽成纯函数。
 *
 * <h2>为什么要单独抽出来</h2>
 *
 * 补偿的动作只有一行（把差额加回桶），全部难点在「什么时候不该动手」——
 * 四道闸门以及它们的<b>交互</b>。而这个项目里唯一一个由测试而非人工观察发现的缺陷
 * （风控 CMS 在「阈值 3 + 高负载」这个我没想到的组合上仍然恒真），
 * 恰好就是靠测一个纯函数发现的。
 *
 * <p>判据留在 Service 里的话，测它就要 mock 校验器、Redis、配置、JdbcTemplate 四样东西，
 * 于是实际上只会测两三条主路径，而闸门的交互（不稳定采样是否污染连续计数？
 * 残差跳变后计数该不该重置？超上限时连续计数怎么办？）就全靠脑补。
 * <b>难测的代码不会被测，然后就会在没测过的那个组合上出事。</b>
 *
 * <p>所以这里没有任何 I/O：输入是几个数，输出是一个决定。
 * Service 只负责把 I/O 接上去。
 */
public final class ReconcileDecider {

    private ReconcileDecider() {
    }

    /** 对账能做出的全部动作。 */
    public enum Action {
        /** 采样期间有号源在归还，读数无意义。<b>不判断也不计数。</b> */
        SKIP_UNSTABLE,
        /** 账目平衡。 */
        BALANCED,
        /** 负残差 = 潜在超卖。拒绝自动处置，告警等人。 */
        REFUSE_OVERSOLD,
        /** 残差还没稳定复现足够次数，继续观察。 */
        OBSERVING,
        /** 残差超过单次补偿上限，更可能是校验器算错了。拒绝并告警。 */
        REFUSE_TOO_LARGE,
        /** 预演：会补，但这次不动手。 */
        DRY_RUN,
        /** 补偿。 */
        COMPENSATE
    }

    /**
     * 一次判断的结果。
     *
     * @param nextLastVanished  下一轮要记住的「上次残差」
     * @param nextConsecutive   下一轮要记住的「连续相同次数」
     */
    public record Decision(
            Action action,
            int amount,
            int nextLastVanished,
            int nextConsecutive,
            String reason
    ) {
        public boolean acts() {
            return action == Action.COMPENSATE;
        }
    }

    /**
     * 判断这一轮该不该补、补多少。
     *
     * @param vanished     守恒残差。&gt;0 少卖（号源卡住了），&lt;0 潜在超卖
     * @param stableSample 采样期间系统是否稳定（没有号源正在归还）
     * @param lastVanished 上一轮观测到的残差
     * @param consecutive  上一轮的「连续相同次数」
     * @param threshold    要连续看到同一个残差多少次才动手
     * @param cap          单次补偿上限
     * @param dryRun       只判断不动手
     */
    public static Decision decide(int vanished, boolean stableSample,
                                  int lastVanished, int consecutive,
                                  int threshold, int cap, boolean dryRun) {

        // 闸门②：采样不稳定。
        //
        // <b>关键在于「不计数」而不只是「不动手」。</b>如果这里推进连续计数，
        // 一个反复出现的采样偏移就能凑够次数触发补偿 ——
        // 而那个残差本来就不存在，补进去就是凭空造号（自己制造超卖）。
        // 所以要原样把状态传下去，就当这一轮没发生过。
        if (!stableSample) {
            return new Decision(Action.SKIP_UNSTABLE, 0, lastVanished, consecutive,
                    "采样期间有号源正在归还，本轮不判定（不计入连续次数）");
        }

        // 账目平衡：状态清零。系统自己好了，之前攒的连续次数不该留着 ——
        // 留着的话，下次出现同样的残差会「提前」够数。
        if (vanished == 0) {
            return new Decision(Action.BALANCED, 0, 0, 0, "账目平衡");
        }

        // 闸门①：方向不对称。
        //
        // 少卖是号源卡住了，加回去最坏是把一个本来就存在的号重新放出来；
        // 超卖意味着<b>已经有患者拿到了号</b>，自动「回收」就是取消一个真实预约。
        // <b>可逆的方向可以自动化，不可逆的方向必须留给人。</b>
        if (vanished < 0) {
            return new Decision(Action.REFUSE_OVERSOLD, 0, vanished, 0,
                    "占号比总号数多 " + (-vanished) + " 个（潜在超卖），"
                            + "拒绝自动处置：回收号源等于取消真实患者的预约，必须人工核查");
        }

        // 闸门③：连续多次看到<b>同一个数</b>。
        //
        // 判据不是「连续都非零」而是「连续是同一个数」：数字还在变说明系统还在动
        // （消费在途、租约即将被回收），此时补偿会补到一个中间态上。
        int nextConsecutive = (vanished == lastVanished) ? consecutive + 1 : 1;
        if (nextConsecutive < threshold) {
            return new Decision(Action.OBSERVING, 0, vanished, nextConsecutive,
                    "残差 " + vanished + " 已连续 " + nextConsecutive + "/" + threshold + " 次，继续观察");
        }

        // 闸门④：单次补偿上限。
        //
        // 注意这里<b>保留</b>连续计数而不清零：残差确实稳定复现了，这是真实情况，
        // 清零会让下一轮又从 1 开始数、反复打同一条告警。
        // 保留则表示「这个状态一直在，只是我不敢动手」。
        if (vanished > cap) {
            return new Decision(Action.REFUSE_TOO_LARGE, 0, vanished, nextConsecutive,
                    "残差 " + vanished + " 超过单次上限 " + cap + "，拒绝自动处置："
                            + "这种量级更可能是校验器算错而非真丢了这么多号，补偿会把错误放大成超卖");
        }

        if (dryRun) {
            // 预演不改变状态：否则「预演一次」会消耗掉真实执行所需的连续次数。
            return new Decision(Action.DRY_RUN, vanished, lastVanished, consecutive,
                    "dry-run：会把 " + vanished + " 个号源补回活跃桶");
        }

        // 动手后清零：补完账目应该就平了，下一轮从头开始判断。
        return new Decision(Action.COMPENSATE, vanished, 0, 0,
                "补偿 " + vanished + " 个号源到活跃桶");
    }
}
