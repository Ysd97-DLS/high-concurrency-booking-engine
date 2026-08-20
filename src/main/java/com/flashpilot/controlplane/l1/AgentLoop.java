package com.flashpilot.controlplane.l1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.MetricsCollector;
import com.flashpilot.controlplane.MetricsSnapshot;
import com.flashpilot.controlplane.config.ConfigAuditRepository;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.controlplane.guard.ChangeProposal;
import com.flashpilot.controlplane.guard.GuardRail;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * L1 Agent 控制回路。
 *
 * <p><b>三个关键的工程决策，都是面试可以聊很久的点：</b>
 * <ol>
 *   <li><b>事件驱动而不是定时轮询</b>：只有指标越界才唤醒模型。绝大多数时间它根本不被调用，
 *       所以 token 成本几乎为零；而定时轮询会在系统健康时白烧钱。</li>
 *   <li><b>观察窗口</b>：一次变更后必须等满窗口、采到新指标才允许下一次决策。
 *       这是防震荡的第一手段，比在提示词里写「请不要频繁调整」可靠得多。</li>
 *   <li><b>先 dry-run 再执行</b>：提案先空跑一遍全部护栏，被驳回就根本不写入。
 *       审计表里会留下驳回记录——这恰恰是护栏有效的证据。</li>
 * </ol>
 *
 * <p>还有一条兜底：整个 Agent 不可用（没配 key、调用超时、返回非结构化）时，
 * 系统自动退化为纯 L0 规则控制，数据面完全不受影响。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 没有流量就不值得叫醒模型。 */
    private static final double MIN_QPS_TO_ACT = 50.0;

    /**
     * 决策耗时超过这个值就要重新确认触发条件还成立。
     *
     * <p>取 15 秒的依据：L0 每秒调一次，15 秒足够它把一次超调纠回来；
     * 而 LLM 正常耗时是 7–14 秒（实测 deepseek-v4-pro 空闲时），
     * 也就是说<b>正常情况下这道检查不会触发</b>，只有真的慢到异常才会拦。
     */
    private static final long STALE_DECISION_MS = 15_000;

    private static final int TIMELINE_CAPACITY = 50;

    private final LlmClient llm;
    private final MetricsCollector collector;
    private final GuardRail guard;
    private final HotConfigService hotConfig;
    private final ConfigAuditRepository audit;
    private final FlashPilotProperties props;
    private final SeckillMetrics metrics;
    private final ObjectMapper json;

    private final AtomicLong lastDecisionAt = new AtomicLong(0);

    /**
     * 是否有一次 LLM 调用正在飞。
     *
     * <p><b>这个保护原来不存在，而观察窗口挡不住它。</b>{@code lastDecisionAt} 是在调用
     * <i>开始</i>时设置的，窗口 20 秒；而 deepseek-v4-pro 实测单次要 14 秒、
     * 高压下更慢（超时上限现在是 60 秒）。于是很容易出现：
     * 第 20 秒窗口过期，而第一次调用还在飞，第 22 秒的 tick 就又发起一次 ——
     * <b>两个调度线程同时阻塞在 LLM 上，而它们看到的是同一份指标</b>，
     * 可能对同一个参数提出两个方向相反的提案。
     *
     * <p>护栏能挡住第二个提案（冷却期），但那是最后一道防线在替上游的并发问题擦屁股。
     * 而且两个调度线程一起阻塞 60 秒，对一个 18 线程的共享池是实打实的占用。
     */
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 上一次检查为什么没有决策。
     *
     * <p>加这个是因为排查「L1 怎么一直不动」花了太多步：指标看着都超阈值、手工触发又正常，
     * 而自动路径有 5 个门（LLM 可用性、最小 QPS、唤醒判据、观察窗口、in-flight），
     * <b>每一个都是静默 return</b>，从外面完全看不出卡在哪一道。
     *
     * <p>这和这个项目反复出现的那类问题同源：一个东西「没在工作」，
     * 而它不工作的原因不可观测。运维问「Agent 为什么不调参」时，
     * 这一行就是答案，不用去读源码数 if。
     */
    private final java.util.concurrent.atomic.AtomicReference<String> lastSkip =
            new java.util.concurrent.atomic.AtomicReference<>("还没检查过");
    private final ConcurrentLinkedDeque<Map<String, Object>> timeline = new ConcurrentLinkedDeque<>();

    public AgentLoop(LlmClient llm, MetricsCollector collector, GuardRail guard, HotConfigService hotConfig,
                     ConfigAuditRepository audit, FlashPilotProperties props, SeckillMetrics metrics,
                     ObjectMapper json) {
        this.llm = llm;
        this.collector = collector;
        this.guard = guard;
        this.hotConfig = hotConfig;
        this.audit = audit;
        this.props = props;
        this.metrics = metrics;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "2000")
    public void maybeDecide() {
        if (!llm.available()) {
            lastSkip.set("Agent 未启用或没配 API key");
            return;
        }
        MetricsSnapshot s = collector.latest();
        if (s.requestQps() < MIN_QPS_TO_ACT) {
            lastSkip.set(String.format("流量太低不值得动（requestQps=%.0f < %.0f）",
                    s.requestQps(), MIN_QPS_TO_ACT));
            return;
        }
        String trigger = triggerOf(s);
        if (trigger == null) {
            lastSkip.set(String.format("指标都在阈值内（P99=%.1fms 误拒=%.1f%% 积压=%d）",
                    s.p99Ms(), s.rejectRate() * 100, s.streamPending()));
            return;
        }
        long window = props.control().agent().observeWindowMs();
        long since = System.currentTimeMillis() - lastDecisionAt.get();
        if (since < window) {
            lastSkip.set(String.format("上次决策才过了 %dms，观察窗口 %dms 还没满（触发条件已满足：%s）",
                    since, window, trigger));
            return;
        }
        // 上一次调用还在飞就不要再发 —— 观察窗口挡不住这种情况，见 inFlight 的注释。
        if (!inFlight.compareAndSet(false, true)) {
            lastSkip.set("上一次 LLM 调用还在进行中（触发条件已满足：" + trigger + "）");
            return;
        }
        try {
            lastSkip.set("正在决策：" + trigger);
            decideNow(s, trigger, false);
        } finally {
            inFlight.set(false);
        }
    }

    /** 手工触发一次决策，给 demo 和调试用。{@code dryRun=true} 时只看提案不执行。 */
    public Map<String, Object> tickNow(boolean dryRun) {
        MetricsSnapshot s = collector.latest();
        if (!llm.available()) {
            return Map.of("skipped", "Agent 未启用或未配置 API key，系统当前只跑 L0 规则控制");
        }
        String trigger = triggerOf(s);
        return decideNow(s, trigger == null ? "manual" : trigger, dryRun);
    }

    private Map<String, Object> decideNow(MetricsSnapshot s, String trigger, boolean dryRun) {
        lastDecisionAt.set(System.currentTimeMillis());
        long startedAt = System.currentTimeMillis();

        String payload = buildPayload(s, trigger);
        Optional<AgentDecision> maybe = llm.decide(systemPrompt(), payload);


        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", startedAt);
        event.put("trigger", trigger);
        event.put("model", llm.model());
        event.put("latencyMs", 0L);
        event.put("p99Ms", Math.round(s.p99Ms() * 100) / 100.0);
        event.put("limitQpsBefore", s.limitQps());

        // ---------- 决策回来之后，先确认世界还是那个世界 ----------
        //
        // 这一段是实测逼出来的：LLM 调用<b>可能耗时几分钟</b>（推理模型 + 服务端负载，
        // 实测撞到过 2 分 40 秒）。而 @Scheduled 的 fixedDelay 语义是「上一次执行完成后
        // 再等 N 秒」，所以调用阻塞期间整个 L1 是停摆的 —— 这一点还好，L0 在兜底。
        //
        // <b>真正危险的是：提案基于的是几分钟前的那份快照。</b>
        // 那时候「P99=159ms、误拒率 54%」，而三分钟后压测早结束了、系统空转，
        // 这时候去执行一个「把限流砍到 3000」的提案，是拿过期诊断开一副现在的药。
        // 控制面的全部意义是闭环，而闭环里最不能有的就是这种延迟。
        //
        // 所以：决策返回后重新取一份快照，如果原来的触发条件已经不成立，就放弃执行。
        // 放弃不是失败 —— 它恰恰说明系统自己恢复了，本来就不需要这次调整。
        long staleMs = System.currentTimeMillis() - startedAt;
        if (!dryRun && staleMs > STALE_DECISION_MS) {
            MetricsSnapshot now = collector.latest();
            String stillTriggered = triggerOf(now);
            if (stillTriggered == null) {
                event.put("outcome", "STALE_DROPPED");
                event.put("latencyMs", staleMs);
                event.put("note", String.format(
                        "决策耗时 %dms，回来时触发条件已消失（现在 P99=%.1fms 误拒=%.1f%% 积压=%d），"
                        + "放弃执行 —— 提案基于的是 %d 秒前的快照，那是过期诊断",
                        staleMs, now.p99Ms(), now.rejectRate() * 100, now.streamPending(), staleMs / 1000));
                maybe.ifPresent(d -> event.put("droppedProposal",
                        d.noChange() ? "(no-change)" : d.param() + " → " + d.value()));
                push(event);
                log.warn("[L1] 决策耗时 {}ms，回来时系统已恢复，放弃执行提案（避免用过期诊断调参）", staleMs);
                return event;
            }
        }

        if (maybe.isEmpty()) {
            // **失败也要记耗时。** 原来这里不写 latencyMs，于是它留在初始值 0，
            // 而时间线上「LLM_UNAVAILABLE + 延迟 0ms」看起来像「根本没发出请求」——
            // 真实情况是请求发了、等了满 20 秒读超时。
            // 耗时正好是判断「是不是超时」最直接的证据，少了它就得去翻源码。
            long elapsed = System.currentTimeMillis() - startedAt;
            long timeout = props.control().agent().timeoutMs();
            event.put("latencyMs", elapsed);
            event.put("outcome", "LLM_UNAVAILABLE");
            event.put("note", elapsed >= timeout - 500
                    ? "调用超时（耗时 " + elapsed + "ms ≈ 上限 " + timeout + "ms），本轮由 L0 兜底"
                    : "调用失败（耗时 " + elapsed + "ms），本轮由 L0 兜底");
            push(event);
            return event;
        }

        AgentDecision d = maybe.get();
        event.put("diagnosis", d.diagnosis());
        event.put("latencyMs", System.currentTimeMillis() - startedAt);

        if (d.noChange()) {
            event.put("outcome", "NO_CHANGE");
            // 记进审计表，让决策时间线上能看到「Agent 看过了但选择不动」
            hotConfig.recordRejected("(no-change)", null, ChangeProposal.SOURCE_AGENT,
                    d.diagnosis(), "Agent 判断无需调整");
            metrics.agentDecision(false);
            push(event);
            log.info("[L1] Agent 判断无需调整：{}", d.diagnosis());
            return event;
        }

        ChangeProposal proposal = ChangeProposal.agent(d.param(), d.value(),
                "[归因] " + d.diagnosis() + " [动作] " + d.reason());
        event.put("param", d.param());
        event.put("proposedValue", d.value());
        event.put("reason", d.reason());

        // ① 先 dry-run 走一遍全部护栏
        GuardRail.GuardResult dry = guard.submit(proposal, true);
        event.put("dryRun", Map.of(
                "wouldApply", dry.wouldApply(),
                "value", dry.appliedValue() == null ? "-" : dry.appliedValue(),
                "note", dry.note() == null ? "" : dry.note()));

        if (!dry.wouldApply()) {
            event.put("outcome", "REJECTED_BY_GUARD");
            metrics.agentDecision(false);
            push(event);
            log.info("[L1] 提案被护栏驳回 param={} value={} note={}", d.param(), d.value(), dry.note());
            return event;
        }
        if (dryRun) {
            event.put("outcome", "DRY_RUN_ONLY");
            push(event);
            return event;
        }

        // ② dry-run 通过才真正执行
        GuardRail.GuardResult applied = guard.submit(proposal, false);
        event.put("outcome", applied.accepted() ? "APPLIED" : "REJECTED_BY_GUARD");
        event.put("appliedValue", applied.appliedValue());
        event.put("guardNote", applied.note());
        event.put("configVersion", applied.version());
        metrics.agentDecision(applied.accepted());
        push(event);

        if (applied.accepted()) {
            log.info("[L1] Agent 变更生效 {} -> {}，归因：{}",
                    d.param(), HotConfigService.fmt(applied.appliedValue()), d.diagnosis());
        }
        return event;
    }

    /** 哪个指标越界了。返回 null 表示一切正常，不需要叫醒模型。 */
    private String triggerOf(MetricsSnapshot s) {
        FlashPilotProperties.Control.Agent cfg = props.control().agent();
        if (s.p99Ms() > cfg.triggerP99Ms()) {
            return String.format("P99=%.1fms 超过唤醒阈值 %.1fms", s.p99Ms(), cfg.triggerP99Ms());
        }
        if (s.rejectRate() > cfg.triggerRejectRate()) {
            return String.format("误拒率=%.1f%% 超过唤醒阈值 %.1f%%",
                    s.rejectRate() * 100, cfg.triggerRejectRate() * 100);
        }
        if (s.streamPending() > cfg.triggerStreamPending()) {
            return String.format("消费积压=%d 条超过唤醒阈值 %d", s.streamPending(), cfg.triggerStreamPending());
        }
        if (s.anomalies() > 0) {
            return "出现状态不一致告警 " + s.anomalies() + " 次";
        }
        return null;
    }

    private String systemPrompt() {
        StringBuilder bounds = new StringBuilder();
        for (ConfigParam p : ConfigParam.values()) {
            bounds.append(String.format("- %s：允许区间 [%s, %s]%n",
                    p.key(), HotConfigService.fmt(p.min()), HotConfigService.fmt(p.max())));
        }
        return """
                你是一个高并发秒杀系统的流量控制助手，负责在活动进行中调整运行参数。

                你的目标，按优先级排序：
                1. 不出现超卖或少卖（这一条由系统的库存机制保证，你不要试图用参数去补救）。
                2. 让已经抢到的用户拿到订单：消费积压（stream_pending）升高时应降低放行速率。
                3. 把 P99 延迟压在 SLO 以内。
                4. 在满足以上前提下尽量减少误拒（reject_rate），也就是尽量多放人进来。

                你可以调整的参数及其允许区间：
                %s
                参数含义：
                - limit.qps：每秒放行的请求数。这是最直接的流量闸门。
                - stock.buckets：参与哈希的活跃库存桶数。bucket_skew 偏高说明流量集中在少数桶上，
                  可以适当增加桶数；注意桶数越多，借调时的遍历越慢。
                - stock.segment：本地号段大小。调大能减少 Redis 访问（segment_hit_ratio 会上升），
                  但会让库存更多地滞留在各实例手里，活动尾声更容易分配不均。
                - stock.tail：进入尾部单件模式的剩余量阈值。
                - stock.segmentEnabled：0 表示强制关闭号段模式（全部走单件直扣），1 为正常。
                  只在怀疑号段机制本身出问题时才设为 0，这会显著降低吞吐。

                重要约束：
                - 一次只能调整一个参数。请挑影响最直接的那一个。
                - 你的提案还会经过白名单、区间钳制、单次幅度限制和冷却期检查，可能被修正或驳回。
                - 参考「最近变更」：如果上一次变更刚刚生效、效果还看不出来，请调用 report_no_change 等待。
                - 先给出归因（diagnosis）再给动作。归因要指出是哪个环节导致的，不要只重复指标数值。
                """.formatted(bounds.toString());
    }

    private String buildPayload(MetricsSnapshot s, String trigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger", trigger);
        payload.put("slo", Map.of(
                "p99_ms", props.control().l0().p99SloMs(),
                "note", "P99 目标；超出即需处理"));
        payload.put("metrics", s.toAgentSummary());
        payload.put("current_config", hotConfig.snapshot());

        List<Map<String, Object>> history = new ArrayList<>();
        for (ConfigAuditRepository.Entry e : audit.recentAccepted(5)) {
            history.add(Map.of(
                    "at", e.createdAt() == null ? "" : e.createdAt(),
                    "param", e.param(),
                    "from", e.oldValue() == null ? "" : e.oldValue(),
                    "to", e.newValue() == null ? "" : e.newValue(),
                    "source", e.source(),
                    "reason", e.reason() == null ? "" : e.reason()));
        }
        payload.put("recent_changes", history);

        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private void push(Map<String, Object> event) {
        timeline.addFirst(event);
        while (timeline.size() > TIMELINE_CAPACITY) {
            timeline.pollLast();
        }
    }

    /** 决策时间线，给 demo 页面和实验复盘用。 */
    public List<Map<String, Object>> timeline() {
        return Collections.unmodifiableList(new ArrayList<>(timeline));
    }

    /** 上一次自动检查为什么没有决策。给「Agent 怎么一直不动」这个问题一个直接答案。 */
    public String lastSkipReason() {
        return lastSkip.get();
    }

    public void reset() {
        lastDecisionAt.set(0);
        timeline.clear();
        lastSkip.set("实验重置，还没检查过");
    }

    public boolean enabled() {
        return llm.available();
    }
}
