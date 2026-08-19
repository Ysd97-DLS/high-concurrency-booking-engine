package com.flashpilot.controlplane.config;

import java.util.Optional;

/**
 * 热参数白名单。
 *
 * <p>这个枚举就是护栏的第一道：<b>控制面（包括 LLM Agent）只能改这里列出的参数</b>，
 * 任何其它键名一律驳回。取值也会被钳制在 [min, max] 内，
 * 所以 Agent 不可能把限流阈值改成 0，也不可能把桶数改成 10000。
 */
public enum ConfigParam {

    /** 每秒放行的请求数。控制面调得最频繁的就是它。 */
    LIMIT_QPS("limit.qps", 100, 200_000),

    /**
     * 参与哈希的「活跃桶数」。
     *
     * <p>注意这里有个刻意的设计：物理桶数固定为 {@link com.flashpilot.dataplane.stock.StockKeys#MAX_BUCKETS}，
     * 这个参数只决定请求哈希到前几个桶。这样调小它<b>不会把库存孤立在失效的桶里</b>——
     * 借调循环始终扫描全部物理桶，会把剩在高位桶里的货捞出来。
     * 如果按「物理桶数 = 配置值」实现，调小的瞬间高位桶的库存就凭空消失了，这是个很容易踩的坑。
     */
    ACTIVE_BUCKETS("stock.buckets", 1, 32),

    /** 号段大小。调大省 Redis，调小更公平。 */
    SEGMENT_SIZE("stock.segment", 1, 500),

    /** 全局剩余低于此值进入尾部单件模式。 */
    TAIL_THRESHOLD("stock.tail", 0, 10_000),

    /** 降级开关：0 = 强制关闭号段模式（全部走单件直扣），1 = 正常。 */
    SEGMENT_ENABLED("stock.segmentEnabled", 0, 1),

    /**
     * 风控严格度：同一患者在一个统计窗口内允许的请求次数，超过就降权。
     *
     * <p>这是挂号场景特有的一个<b>有真实业务收益的可调参数</b>：
     * 调太严会伤到反复刷新页面的真实患者（老人尤其容易被误判），
     * 调太松则放过批量代抢脚本。而合适的位置随流量规模变化——
     * 放号瞬间人人都在高频重试，阈值该放宽；平峰期高频就是异常。
     * 正因为「最优值随时间变化」，它才值得交给控制面自动调，而不是写死在配置里。
     */
    RISK_THRESHOLD("riskcontrol.threshold", 3, 500),

    /**
     * 放号节奏：把一批号分多少秒放完。0 = 一次全放。
     *
     * <p>真实运营手段：与其在 7:00:00 一秒放完 50 个专家号引发洪峰，
     * 不如在 7:00–7:05 内分批放出。既削峰，又降低脚本的命中率
     * （脚本盯着整点，分批放出后它必须持续轮询才能抢到，成本上升）。
     */
    RELEASE_SPREAD_SECONDS("release.spreadSeconds", 0, 600),

    /**
     * 慢车道速率：被风控降权的流量每秒总共能通过多少个请求。
     *
     * <p><b>必须是绝对值，不能是主限流的比例。</b>第一版写成
     * {@code LIMIT_QPS × 5%}，而主限流默认 20000，算出来 1000/s ——
     * 对降权流量来说这根本不叫慢，实测黄牛 80 个请求全部通过。
     * 降权的语义是「与整体负载无关地压低通行速率」，一旦跟主限流挂钩就自相矛盾了。
     *
     * <p>取值是个真实的权衡，也正因此值得交给控制面调：
     * 太低会让被误判的真实患者（老人反复刷新很容易触发频次判据）几乎挂不上号；
     * 太高则挡不住批量代抢。
     */
    SLOW_LANE_QPS("riskcontrol.slowLaneQps", 1, 2000);

    private final String key;
    private final double min;
    private final double max;

    ConfigParam(String key, double min, double max) {
        this.key = key;
        this.min = min;
        this.max = max;
    }

    public String key() {
        return key;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** 钳制到合法区间。护栏用它把越界的提案「修正」而不是直接丢掉。 */
    public double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }

    public static Optional<ConfigParam> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (ConfigParam p : values()) {
            if (p.key.equals(key)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
