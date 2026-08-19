package com.flashpilot.controlplane;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制面看到的世界。L0 规则控制器和 L1 Agent 读的都是这个快照。
 *
 * <p>注意 {@code effectiveQps} 和 {@code rejectRate} 是<b>窗口内的增量</b>而不是累计值——
 * 控制面要知道「现在怎么样」，累计平均值会把刚发生的抖动摊平掉，看不出问题。
 *
 * <p><b>{@code rejectRate} 只含主限流的拒绝，不含风控丢弃</b>（后者是
 * {@code riskDropRate}）。两者必须分开，因为控制面唯一能调的是限流速率：
 * 把风控的处置混进 rejectRate，控制面就会为了"降低误拒"去放宽限流，
 * 而误拒的真实来源是风控——它调什么都没用，只会一路砍到底。
 * 实测过这个后果：限流被从 20000 砍到 6860，而真实原因跟容量毫无关系。
 */
public record MetricsSnapshot(
        long timestampMs,
        double windowSeconds,

        // 流量
        double effectiveQps,
        double requestQps,
        /** 主限流拒绝率。控制面要优化的就是它，也只有它是控制面能影响的。 */
        double rejectRate,
        /** 风控慢车道丢弃率。控制面<b>看得到但管不着</b>，调限流对它无效。 */
        double riskDropRate,
        double p99Ms,

        // 库存
        int bucketSum,
        int leaseHeld,
        double bucketSkew,
        int activeBuckets,
        boolean tailMode,

        // 号段
        double segmentHitRatio,
        long refills,
        long steals,
        long anomalies,

        // 消息
        long streamPending,
        long streamLength,
        long consumed,
        long duplicate,
        long deadLetter,
        long oversoldBlocked,

        // 控制面自身
        long limitQps,
        long configVersion,

        // 本轮全程延迟（/verify/reset 清零）。
        // 和上面的 p99Ms 不是一回事：p99Ms 是 10 秒滚动窗口，给控制面看「现在」；
        // 这几个是自本轮重置以来的全程统计，给实验报告看「这一轮到底多快」。
        double runP50Ms,
        double runP95Ms,
        double runP99Ms,
        double runMaxMs,
        long runSamples
) {

    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(System.currentTimeMillis(), 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, false,
                1, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                0, 0,
                0, 0, 0, 0, 0);
    }

    /**
     * 喂给 LLM 的紧凑摘要。
     *
     * <p>刻意不把原始时序数据丢给模型：一是 token 贵，二是模型在长数字序列上的表现远不如
     * 在结构化摘要上。这里做的就是「服务端先聚合，再让模型判断」。
     */
    public Map<String, Object> toAgentSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p99_ms", round(p99Ms));
        m.put("effective_qps", round(effectiveQps));
        m.put("request_qps", round(requestQps));
        m.put("reject_rate", round(rejectRate));
        // 也告诉 Agent 风控丢了多少 —— 否则它看到"误拒率不高但成交很低"会困惑，
        // 可能去调它根本不该动的参数。让模型看到全貌比让它猜便宜。
        m.put("risk_drop_rate", round(riskDropRate));
        m.put("current_limit_qps", limitQps);
        m.put("stock_remaining_in_buckets", bucketSum);
        m.put("stock_held_by_instances", leaseHeld);
        m.put("bucket_skew", round(bucketSkew));
        m.put("active_buckets", activeBuckets);
        m.put("tail_mode", tailMode);
        m.put("segment_hit_ratio", round(segmentHitRatio));
        m.put("segment_steal_count", steals);
        m.put("stream_pending", streamPending);
        m.put("consumed", consumed);
        m.put("dead_letter", deadLetter);
        m.put("oversold_blocked", oversoldBlocked);
        m.put("state_anomalies", anomalies);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
