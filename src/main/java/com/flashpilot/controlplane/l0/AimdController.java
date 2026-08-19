package com.flashpilot.controlplane.l0;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.MetricsCollector;
import com.flashpilot.controlplane.MetricsSnapshot;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.guard.ChangeProposal;
import com.flashpilot.controlplane.guard.GuardRail;

/**
 * L0 规则控制器：AIMD（加性增、乘性减），思路直接来自 TCP 拥塞控制。
 *
 * <p><b>它的定位是「兜底」，不是「最优」。</b>纯代码、无外部依赖、决策耗时微秒级，
 * 所以它永远在线；Agent 挂掉、超时、没配 API key 的时候，系统就退化成只有这一层，
 * 功能和安全性都不受影响。
 *
 * <p><b>它的短板也要说清楚</b>（面试会问「有规则了为什么还要 Agent」）：
 * 它只会「P99 涨了就降速」，答不了「P99 为什么涨」——是桶倾斜、消费积压，还是慢查询？
 * 跨指标归因和多目标权衡（吞吐 vs 误拒 vs 积压）是规则很难穷举的，那才是 L1 的活。
 */
@Component
public class AimdController {

    private static final Logger log = LoggerFactory.getLogger(AimdController.class);

    /**
     * 低于这个请求量就不动作。
     *
     * <p>很重要：没有流量时 P99 是 0（滚动窗口里没有样本），如果照常执行「健康就加性上调」，
     * 阈值会一路涨到上限，下一次压测开始时等于没有限流。这个坑不踩过一次不会想到。
     */
    private static final double MIN_QPS_TO_ACT = 50.0;

    /**
     * 「过度限流」的判定门槛：误拒率高于此值时才考虑快速恢复。
     *
     * <p><b>这里修的是一个真实的控制律缺陷，值得完整记下来。</b>
     *
     * <p>原本的实现只有「乘性减 ×0.7 / 加性增 +1000」。这套参数下：
     * 从 20000 掉到 2352 只需约 6 秒（每秒可乘性降一次），
     * 而涨回去需要约 53 秒（+1000 且要求连续 3 周期健康）—— <b>9 倍的不对称</b>。
     * 结果是 30 秒的压测里系统根本没机会恢复，全程以 92% 的误拒率运行。
     *
     * <p>更麻烦的是信号本身是<b>循环</b>的：{@code p99Ms} 统计的是<i>被放行</i>请求的延迟，
     * 限流越狠、放行越少、P99 越「健康」，而 P99 又是决定要不要继续限流的依据。
     * 实测抓到的那条日志就是证据：{@code 误拒率 92.4% 而 P99=0.0ms}。
     * 单看 P99 会得出「系统很健康」，单看误拒率会得出「限得太狠」，两者必须一起看。
     *
     * <p>所以判据改成三条同时成立：<b>误拒率高</b> + <b>P99 远低于 SLO</b> + <b>积压不高</b>
     * （第三条由「没进降速分支」隐含保证）。三条同时成立才是「有余力却在白拒」的确凿证据，
     * 这时候按比例快速恢复，而不是一次 +1000 慢慢爬。
     */
    private static final double OVER_THROTTLE_REJECT_RATE = 0.20;

    /** 判定过度限流时的恢复倍率。比降速的 0.7 温和些，避免又冲过头形成震荡。 */
    private static final double OVER_THROTTLE_RECOVER_FACTOR = 1.4;

    /** P99 要低于 SLO 的这个比例，才算「远低于」，避免在 SLO 边缘反复横跳。 */
    private static final double HEALTHY_P99_RATIO = 0.5;

    /**
     * 放行阈值的下限。低于这个值等于把系统限死，宁可积压也不该到这一步。
     *
     * <p>取值依据是实测的消费端排空速率（约 5000 条/秒）：只要放行速率低于排空速率，
     * 积压一定会下降，再往下砍没有任何收益，只是在白拒用户。
     */
    private static final long MIN_LIMIT_QPS = 1000;

    /**
     * 积压被认为「正在排空」的判据：本周期积压 < 上周期的这个比例。
     *
     * <p><b>这条是修一个严重超调 bug 的核心，必须记住。</b>
     *
     * <p>原实现的降速条件只看积压的<b>绝对值</b>：{@code streamPending > 阈值} 就乘性降速。
     * 问题是控制周期 1 秒，而积压排空要几十秒 —— <b>执行器比系统响应快 30 倍</b>。
     * 于是控制器对着一个已经在好转的信号连续砍刀。实测日志：
     * <pre>
     *   降速 4802 -> 3361（积压 65039）
     *   降速 3361 -> 2352（积压 60274）
     *   ...
     *   降速  394 ->  275（积压  5408）
     * </pre>
     * 12 次连续降速、0 次上调，阈值从 20000 崩到 275（73 倍），误拒率 99.2%，
     * 30 万库存只卖出 13.4 万 —— 而积压那一列<b>全程都在下降</b>，
     * 说明第 2、3 刀之后的每一刀都是纯粹的过度修正。
     *
     * <p>修法是把判据从「积压的<b>水位</b>」改成「积压的<b>趋势</b>」：
     * 只要积压在下降，就说明当前阈值已经够用了，不再继续砍。
     * 这也是控制系统的通用教训 —— 对慢响应的被控对象，要看导数而不只看当前值。
     *
     * <p><b>第一版这里用的是「跌破上周期的 97%」，结果判据完全没生效</b>：
     * 实测排空速率只有每周期 1~2%（6.5 万条积压、每秒排掉约一千条），
     * 3% 的门槛永远达不到，于是照旧连砍 9 刀。日志把证据摆得很清楚 ——
     * 「积压 64721 条且未见下降（上周期 65421）」，明明在降却判成没降。
     *
     * <p>所以判据不该带百分比：<b>只要积压在下降，就说明放行速率已经低于排空速率</b>，
     * 这是可以证明的，再砍一刀不可能让它降得更快，只会多拒用户。
     * 用严格小于即可，不需要容差 —— XPENDING 返回的是精确计数，没有测量噪声。
     */

    /** 上一周期的积压量，用于判断趋势。仅由单线程的 {@link #tick()} 访问。 */
    private long prevPending = -1;

    private final MetricsCollector collector;
    private final GuardRail guard;
    private final FlashPilotProperties props;

    private final AtomicInteger healthyCycles = new AtomicInteger();

    public AimdController(MetricsCollector collector, GuardRail guard, FlashPilotProperties props) {
        this.collector = collector;
        this.guard = guard;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${flashpilot.control.l0.interval-ms:1000}")
    public void tick() {
        FlashPilotProperties.Control.L0 cfg = props.control().l0();
        if (!cfg.enabled()) {
            return;
        }
        MetricsSnapshot s = collector.latest();
        if (s.requestQps() < MIN_QPS_TO_ACT) {
            healthyCycles.set(0);
            return;
        }

        // 还在冷却期里就什么都不做。
        //
        // 控制周期 1 秒、冷却期 5 秒，所以 4/5 的提案注定被驳回。实测审计表里
        // 898 条 L0 提案有 651 条（72%）是「冷却期未过」—— 白做的工作是小事，
        // <b>真正的问题是这些噪声把剩下 28% 的真实决策挤成了不可见</b>。
        //
        // 注意这不改变控制律：冷却期本来就限制了每 5 秒才能动一次。
        // 也刻意<b>不动</b> healthyCycles —— 它数的是「连续健康周期」，
        // 而冷却期跟系统健不健康无关，在这里清零或推进都会扭曲加性增的节奏。
        if (guard.inCooldown(ConfigParam.LIMIT_QPS)) {
            return;
        }

        double currentLimit = s.limitQps();

        // 乘性减：P99 超 SLO，或者消费端积压到危险水位。
        // 积压这一条是真实存在的需求：用户「抢到了但没订单」比「没抢到」体验更差，
        // 所以宁可少放一些人进来。
        boolean p99Bad = s.p99Ms() > cfg.p99SloMs();

        // 积压判据：水位高「且没有在排空」才算坏。
        // 只看水位会造成严重超调 —— 见 PENDING_DRAINING_RATIO 的说明。
        long pending = s.streamPending();
        long lastPending = prevPending;          // 先留存，日志和判据都要用原值
        boolean pendingHigh = pending > props.control().agent().triggerStreamPending();
        boolean draining = lastPending >= 0 && pending < lastPending;
        boolean pendingBad = pendingHigh && !draining;
        prevPending = pending;

        // 已经砍到下限就不再砍了。再砍只是白拒用户，对排空没有任何帮助：
        // 放行速率已经低于消费端的排空速率，积压必然在下降。
        if (currentLimit <= MIN_LIMIT_QPS && (p99Bad || pendingBad)) {
            healthyCycles.set(0);
            log.warn("[L0] 已达放行下限 {}，不再降速（P99={}ms 积压={} 条）—— 继续砍只会白拒用户",
                    MIN_LIMIT_QPS, String.format("%.1f", s.p99Ms()), pending);
            return;
        }

        if (p99Bad || pendingBad) {
            healthyCycles.set(0);
            double target = Math.max(MIN_LIMIT_QPS, Math.floor(currentLimit * cfg.decreaseFactor()));
            String reason = p99Bad
                    ? String.format("P99=%.1fms 超过 SLO %.1fms，乘性降速", s.p99Ms(), cfg.p99SloMs())
                    : String.format("Stream 积压 %d 条且未见下降（上周期 %d），乘性降速保护消费端",
                            pending, lastPending);
            GuardRail.GuardResult r = guard.submit(ChangeProposal.rule(ConfigParam.LIMIT_QPS.key(), target, reason));
            if (r.accepted()) {
                log.info("[L0] 降速 {} -> {}（{}）", (long) currentLimit, r.appliedValue().longValue(), reason);
            }
            return;
        }

        // 加性增：连续若干周期健康才允许上调，避免刚降下去就立刻涨回来造成震荡
        if (healthyCycles.incrementAndGet() < cfg.healthyCyclesBeforeIncrease()) {
            return;
        }
        healthyCycles.set(0);

        // 只有在确实被限流拒绝过的时候才值得上调——没人被拒说明当前阈值够用了
        if (s.rejectRate() <= 0.001) {
            return;
        }

        // 走到这里说明：P99 没超 SLO、积压不高（否则已进降速分支）、且有人被拒。
        // 此时要区分两种情形，用的恢复速度完全不同：
        //   a) 过度限流 —— 拒了一大片，但延迟和积压都很健康，说明系统有余力被白白浪费。
        //      按比例快速恢复，否则乘性减 / 加性增的不对称会让系统长期困在过度限流状态。
        //   b) 正常寻优 —— 只有少量被拒，说明已经接近容量边界，保持 AIMD 的加性保守。
        boolean overThrottled = s.rejectRate() > OVER_THROTTLE_REJECT_RATE
                && s.p99Ms() < cfg.p99SloMs() * HEALTHY_P99_RATIO;

        double target;
        String reason;
        if (overThrottled) {
            target = Math.ceil(currentLimit * OVER_THROTTLE_RECOVER_FACTOR);
            reason = String.format(
                    "误拒率 %.1f%% 但 P99 仅 %.1fms（SLO %.1fms）、积压 %d 条，判定为过度限流，按 %.1f 倍快速恢复",
                    s.rejectRate() * 100, s.p99Ms(), cfg.p99SloMs(), s.streamPending(),
                    OVER_THROTTLE_RECOVER_FACTOR);
        } else {
            target = currentLimit + cfg.increaseStep();
            // 注意措辞：不要只说「P99 健康」。P99 是被放行请求的延迟，
            // 限流越狠它越好看，单独拿它当健康证明是循环论证。必须连同误拒率和积压一起陈述。
            reason = String.format("连续 %d 周期健康（P99=%.1fms、积压 %d 条、误拒率 %.1f%%），加性上调",
                    cfg.healthyCyclesBeforeIncrease(), s.p99Ms(), s.streamPending(), s.rejectRate() * 100);
        }
        GuardRail.GuardResult r = guard.submit(ChangeProposal.rule(ConfigParam.LIMIT_QPS.key(), target, reason));
        if (r.accepted()) {
            log.info("[L0] 上调 {} -> {}（{}）", (long) currentLimit, r.appliedValue().longValue(), reason);
        }
    }

    public void reset() {
        healthyCycles.set(0);
        prevPending = -1;      // 不清的话上一轮实验的积压会被当成这一轮的趋势基准
    }
}
