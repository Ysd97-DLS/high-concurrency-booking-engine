package com.flashpilot.controlplane.guard;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;

/**
 * 变更护栏 —— 面试里「Agent 决策错了怎么办」这道题的完整答案。
 *
 * <p>五道关卡，任何来源的变更（包括 L0 规则控制器和 L1 Agent）都必须过：
 * <ol>
 *   <li><b>白名单</b>：参数不在 {@link ConfigParam} 里，直接驳回。它连「改哪个参数」的自由度都没有。</li>
 *   <li><b>区间钳制</b>：越界的值钳到边界而不是驳回——方向对就让它生效一部分，比一刀切更实用。</li>
 *   <li><b>幅度限制</b>：单次变更不超过当前值的一定比例，防止一步跳到极值。</li>
 *   <li><b>冷却期</b>：同一参数两次变更之间必须间隔 cooldown，这是防震荡的关键。
 *       人工变更（MANUAL）可以绕过冷却，因为出事时人得能立刻干预。</li>
 *   <li><b>审计与回滚</b>：无论通过还是驳回都落审计表，且每次变更都能一键回滚。</li>
 * </ol>
 *
 * <p>再加一条兜底：Agent 整个不可用时系统退化为纯 L0 规则控制，功能和安全性都不受影响。
 * 它是可选增强，不是关键路径依赖。
 */
@Component
public class GuardRail {

    private static final Logger log = LoggerFactory.getLogger(GuardRail.class);

    /** 变更被认为「等于没变」的相对阈值，避免刷版本号。 */
    private static final double NOOP_EPSILON = 1e-6;

    private final HotConfigService hotConfig;
    private final FlashPilotProperties props;
    private final Map<ConfigParam, Long> lastChangeAt = new ConcurrentHashMap<>();

    public GuardRail(HotConfigService hotConfig, FlashPilotProperties props) {
        this.hotConfig = hotConfig;
        this.props = props;
    }

    /**
     * 护栏的处理结果。
     *
     * @param accepted     是否真的改了（dry-run 时永远是 false）
     * @param wouldApply   dry-run 下「如果放行会改成多少」
     * @param appliedValue 实际生效的值
     * @param note         护栏做了什么：钳制到边界 / 冷却未过 / 不在白名单 / 变化太小
     * @param version      变更后的配置版本号
     */
    public record GuardResult(
            boolean accepted,
            boolean wouldApply,
            String param,
            Double appliedValue,
            String note,
            long version
    ) {
        public static GuardResult rejected(String param, Double attempted, String note) {
            return new GuardResult(false, false, param, attempted, note, 0);
        }
    }

    public GuardResult submit(ChangeProposal proposal) {
        return submit(proposal, false);
    }

    /**
     * @param dryRun true 时只做全部校验、返回「会改成多少」，但不真的写入。
     *               Agent 的提案先跑一次 dry-run，能在不影响线上的前提下验证提案是否合理。
     */
    public GuardResult submit(ChangeProposal proposal, boolean dryRun) {
        if (proposal == null || proposal.param() == null) {
            return GuardResult.rejected(null, null, "提案为空");
        }

        boolean manual = ChangeProposal.SOURCE_MANUAL.equals(proposal.source());

        // 当前值要在判据之前读出来。不在白名单的键名读不到当前值，传 0 即可 ——
        // 判据的第一步就是白名单，不会用到它。
        Optional<ConfigParam> known = ConfigParam.byKey(proposal.param());
        double current = known.map(hotConfig::get).orElse(0.0);
        long since = known
                .map(p -> System.currentTimeMillis() - lastChangeAt.getOrDefault(p, 0L))
                .orElse(Long.MAX_VALUE);

        // 全部判据都在 GuardDecider 里（纯函数，可单测）。
        // 这个方法只负责把 Redis 读写和审计接上去 —— 判据一旦掺进 I/O 就没人测了，
        // 而护栏是唯一允许 LLM 改生产参数的地方，最不该是「没测过的代码」。
        GuardDecider.Decision d = GuardDecider.decide(
                proposal.param(), proposal.value(), current, manual, since,
                props.control().guard().cooldownMs(),
                props.control().guard().maxChangeRatio(),
                dryRun);

        switch (d.verdict()) {
            case NOT_WHITELISTED, INVALID_VALUE, COOLING_DOWN -> {
                // 被驳回的提案<b>也要进审计</b>：控制面做过什么尝试和它做成了什么一样重要，
                // 「Agent 反复提同一个越界提案」只有从驳回记录里才看得出来。
                hotConfig.recordRejected(d.param() == null ? proposal.param() : d.param().key(),
                        d.target(), proposal.source(), proposal.reason(), d.note());
                return GuardResult.rejected(d.param() == null ? proposal.param() : d.param().key(),
                        d.target(), d.note());
            }
            case NO_OP -> {
                // 空变更不记审计：它什么都没改，记下来只是噪声。
                return new GuardResult(false, false, d.param().key(), d.target(),
                        d.note(), hotConfig.version());
            }
            case DRY_RUN -> {
                return new GuardResult(false, true, d.param().key(), d.target(),
                        d.note(), hotConfig.version());
            }
            case APPLY -> {
                long version = hotConfig.apply(d.param(), d.target(),
                        proposal.source(), proposal.reason(), d.note());
                lastChangeAt.put(d.param(), System.currentTimeMillis());
                return new GuardResult(true, true, d.param().key(), d.target(), d.note(), version);
            }
            default -> throw new IllegalStateException("未处理的护栏处置：" + d.verdict());
        }
    }

    /**
     * 该参数是否还在冷却期内（只读，不产生任何副作用与审计）。
     *
     * <p><b>给自动控制器用的「先问一句」。</b>L0 每秒 tick 一次，而冷却期是 5 秒，
     * 于是 4/5 的提案注定被驳回 —— 实测审计表里 898 条 L0 提案有 651 条
     * （<b>72%</b>）是「冷却期未过」，纯噪声。
     *
     * <p>后果有两层：白做的工作是小事，<b>真正的问题是审计被淹了</b>。
     * 审计的用途是事后看清「控制面做过什么尝试」，
     * 而当 72% 的行都是同一句无信息量的驳回时，剩下 28% 的真实决策就没人翻得到了。
     * 这和对账留档去重、日志限流是同一个问题的第三次出现：
     * <b>一个高频重复的条目会把同一张表里低频重要的条目挤成不可见。</b>
     *
     * <p>注意这<b>不改变控制律</b>：冷却期本来就限制了它每 5 秒才能动一次，
     * 先问一句只是省掉必然失败的那 4 次提交和 4 条审计。
     * 冷却期依然是权威判据 —— 这个方法只是让调用方能提前避让，不是绕过。
     */
    public boolean inCooldown(ConfigParam param) {
        long last = lastChangeAt.getOrDefault(param, 0L);
        return System.currentTimeMillis() - last < props.control().guard().cooldownMs();
    }

    /** 压测前重置冷却状态，避免上一轮实验的冷却影响下一轮。 */
    public void resetCooldowns() {
        lastChangeAt.clear();
        log.info("护栏冷却状态已重置");
    }
}
