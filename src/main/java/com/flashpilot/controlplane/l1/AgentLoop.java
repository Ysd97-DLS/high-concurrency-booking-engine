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
            return;
        }
        MetricsSnapshot s = collector.latest();
        if (s.requestQps() < MIN_QPS_TO_ACT) {
            return;
        }
        String trigger = triggerOf(s);
        if (trigger == null) {
            return;
        }
        long window = props.control().agent().observeWindowMs();
        if (System.currentTimeMillis() - lastDecisionAt.get() < window) {
            return;
        }
        decideNow(s, trigger, false);
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

        if (maybe.isEmpty()) {
            event.put("outcome", "LLM_UNAVAILABLE");
            event.put("note", "调用失败，本轮由 L0 兜底");
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

    public void reset() {
        lastDecisionAt.set(0);
        timeline.clear();
    }

    public boolean enabled() {
        return llm.available();
    }
}
