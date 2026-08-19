package com.flashpilot.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import org.springframework.stereotype.Component;

import com.flashpilot.dataplane.stock.LocalSegmentManager;

/**
 * 所有埋点集中在这里。指标不是为了好看，每一个都对应一个具体用途：
 *
 * <ul>
 *   <li>{@code seckill.effective} —— 只统计真正进入扣减的请求。
 *       用总请求数当 QPS 是自欺欺人：限流拒绝得越快「QPS」越高。</li>
 *   <li>{@code seckill.latency} —— 配了滚动窗口的百分位，因为控制面要读<i>当前</i>的 P99，
 *       而不是进程启动以来的累计 P99。</li>
 *   <li>{@code stock.bucket.skew} —— 桶倾斜度，控制面调桶数的依据。</li>
 *   <li>{@code stock.segment.hitRatio} —— 号段命中率，直接量化 Redis 被卸载了多少。</li>
 *   <li>{@code lease.reclaimed} —— 回收量，少卖风险的先行指标。</li>
 * </ul>
 */
@Component
public class SeckillMetrics {

    private final Timer latency;

    private final Counter success;
    private final Counter soldOut;
    private final Counter duplicate;
    private final Counter rejected;
    /** 被风控慢车道丢弃，和主限流拒绝分开计 —— 见 {@link #riskDropped()} */
    private final Counter riskDropped;
    private final Counter error;
    private final Counter reclaimed;
    private final Counter tailSales;
    /** 号源归还数。少卖风险的另一个先行指标——它和等式③ 的残差应该互相印证。 */
    private final Counter slotsReleased;
    /** 风控降权数。误拒真实患者的代价很高，所以这个指标要和成交量一起看。 */
    private final Counter riskDemotedCounter;
    /** 风控拉黑数。只有失约黑名单会到这一档。 */
    private final Counter riskBlockedCounter;

    // ---------- 全程延迟直方图 ----------
    //
    // 为什么不能直接用上面那个 Timer 出实验报告：Timer 配的是 10 秒滚动窗口，
    // 因为控制面要读「现在」的 P99。而实验脚本是在压测结束、等消费者追平之后才读指标的，
    // 那时窗口里的数据早就过期了，读出来必然是 0 —— 首轮实验的「服务端 P99 = 0.00ms」就是这么来的。
    //
    // 所以这里另开一份「本轮全程」的直方图：只在 /verify/reset 时清零，
    // 压测结束多久之后再读都是准的。两份指标服务两个不同的需求，不要合并。
    private static final int RUN_FINE_BUCKETS = 400;      // 0..200ms，每格 0.5ms
    private static final double RUN_BUCKET_MS = 0.5;
    private final AtomicLong[] runHist = newRunHist();
    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong runMaxNanos = new AtomicLong();

    private static AtomicLong[] newRunHist() {
        // 最后多一格是溢出桶（>200ms），配合 runMaxNanos 报告真实尾部
        AtomicLong[] a = new AtomicLong[RUN_FINE_BUCKETS + 1];
        for (int i = 0; i < a.length; i++) {
            a[i] = new AtomicLong();
        }
        return a;
    }

    // 由 MetricsCollector 定时刷新，Gauge 只读这些持有者，避免 scrape 时打 Redis
    private final AtomicInteger bucketSum = new AtomicInteger();
    private final AtomicInteger leaseHeld = new AtomicInteger();
    private final AtomicLong streamPending = new AtomicLong();
    private final AtomicLong streamLength = new AtomicLong();
    private final AtomicLong skewMilli = new AtomicLong();
    private final AtomicLong currentLimitQps = new AtomicLong();
    private final AtomicLong agentDecisions = new AtomicLong();
    private final AtomicLong agentRejected = new AtomicLong();
    private final AtomicLong configVersion = new AtomicLong();

    public SeckillMetrics(MeterRegistry registry, LocalSegmentManager segments) {
        this.latency = Timer.builder("seckill.latency")
                .description("秒杀接口耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                // 滚动窗口：10 秒过期、3 段缓冲。控制面要的是「现在」的 P99。
                .distributionStatisticExpiry(Duration.ofSeconds(10))
                .distributionStatisticBufferLength(3)
                .register(registry);

        this.success = Counter.builder("seckill.effective").tag("result", "success")
                .description("真正完成扣减的请求数").register(registry);
        this.soldOut = Counter.builder("seckill.result").tag("result", "sold_out").register(registry);
        this.duplicate = Counter.builder("seckill.result").tag("result", "duplicate").register(registry);
        this.rejected = Counter.builder("seckill.result").tag("result", "rejected").register(registry);
        this.riskDropped = Counter.builder("seckill.result").tag("result", "risk_dropped").register(registry);
        this.error = Counter.builder("seckill.result").tag("result", "error").register(registry);
        this.reclaimed = Counter.builder("lease.reclaimed")
                .description("从宕机实例回收的库存件数（少卖风险先行指标）").register(registry);
        this.tailSales = Counter.builder("seckill.tail.sales")
                .description("尾部单件模式成交数").register(registry);
        this.slotsReleased = Counter.builder("clinic.slot.released")
                .description("因超时未支付或退号而归还号池的号源数").register(registry);
        this.riskDemotedCounter = Counter.builder("clinic.risk").tag("action", "demote")
                .description("风控降权次数").register(registry);
        this.riskBlockedCounter = Counter.builder("clinic.risk").tag("action", "block")
                .description("风控拉黑次数（失约黑名单）").register(registry);

        Gauge.builder("stock.bucket.sum", bucketSum, AtomicInteger::get)
                .description("Σ桶剩余").register(registry);
        Gauge.builder("stock.lease.held", leaseHeld, AtomicInteger::get)
                .description("Σ实例本地持有（含待回收）").register(registry);
        Gauge.builder("stock.bucket.skew", skewMilli, h -> h.get() / 1000.0)
                .description("桶倾斜度 (max-min)/mean").register(registry);
        Gauge.builder("stock.segment.hitRatio", segments, LocalSegmentManager::segmentHitRatio)
                .description("本地号段命中率").register(registry);
        Gauge.builder("stream.pending", streamPending, AtomicLong::get)
                .description("Stream 未 ACK 消息数").register(registry);
        Gauge.builder("stream.length", streamLength, AtomicLong::get)
                .description("已发出的成交事件总数").register(registry);
        Gauge.builder("control.limit.qps", currentLimitQps, AtomicLong::get)
                .description("当前生效的限流阈值").register(registry);
        Gauge.builder("control.config.version", configVersion, AtomicLong::get)
                .description("热配置版本号").register(registry);
        Gauge.builder("agent.decisions", agentDecisions, AtomicLong::get)
                .description("Agent 提案被采纳次数").register(registry);
        Gauge.builder("agent.rejected", agentRejected, AtomicLong::get)
                .description("Agent 提案被护栏驳回次数").register(registry);
    }

    // ---------- 记录 ----------

    public void record(long nanos) {
        latency.record(nanos, TimeUnit.NANOSECONDS);
        recordRun(nanos);
    }

    public void success() {
        success.increment();
    }

    public void soldOut() {
        soldOut.increment();
    }

    public void duplicate() {
        duplicate.increment();
    }

    /** 被<b>主限流</b>拒绝。这是控制面要优化的「误拒」，AIMD 的判据之一。 */
    public void rejected() {
        rejected.increment();
    }

    /**
     * 被<b>风控慢车道</b>丢弃。
     *
     * <p><b>必须和 {@link #rejected()} 分开计。</b>这里踩过一个很贵的坑：
     * 原来两者共用 rejected 计数器，于是
     * <ul>
     *   <li>AIMD 控制器的 {@code rejectRate} 把风控的处置当成了自己的容量信号，
     *       看到「误拒率 99.9%」就一路砍限流（实测从 20000 砍到 6860），
     *       而真实原因跟容量毫无关系；</li>
     *   <li>压测报告把 52 万次风控丢弃显示成「限流拒绝」，
     *       <b>整轮性能数字被污染而报告上看不出任何异常</b>。</li>
     * </ul>
     *
     * <p>教训：两个不同原因导致的同一种外部表现（都是 4290 类的"稍后再试"），
     * 在<b>指标上必须可分</b>。否则任何以此为输入的自动控制都在对错误的信号做反应。
     */
    public void riskDropped() {
        riskDropped.increment();
    }

    public void error() {
        error.increment();
    }

    /** 号源被归还号池（超时未支付 / 退号）。 */
    public void slotReleased() {
        slotsReleased.increment();
    }

    public void riskDemoted() {
        riskDemotedCounter.increment();
    }

    public void riskBlocked() {
        riskBlockedCounter.increment();
    }

    public void tailSale() {
        tailSales.increment();
    }

    public void leaseReclaimed(int count) {
        reclaimed.increment(count);
    }

    // ---------- 由 MetricsCollector 刷新 ----------

    public void updateStock(int bucketSumValue, int leaseHeldValue, double skew) {
        bucketSum.set(bucketSumValue);
        leaseHeld.set(leaseHeldValue);
        skewMilli.set(Math.round(skew * 1000));
    }

    public void updateStream(long pending, long length) {
        streamPending.set(pending);
        streamLength.set(length);
    }

    public void updateControl(long limitQps, long version) {
        currentLimitQps.set(limitQps);
        configVersion.set(version);
    }

    public void agentDecision(boolean accepted) {
        if (accepted) {
            agentDecisions.incrementAndGet();
        } else {
            agentRejected.incrementAndGet();
        }
    }

    // ---------- 读 ----------

    // ---------- 全程延迟：实验报告用 ----------

    private void recordRun(long nanos) {
        runCount.incrementAndGet();
        runMaxNanos.accumulateAndGet(nanos, Math::max);
        int idx = (int) ((nanos / 1_000_000.0) / RUN_BUCKET_MS);
        if (idx < 0) {
            idx = 0;
        } else if (idx > RUN_FINE_BUCKETS) {
            idx = RUN_FINE_BUCKETS;      // 溢出桶
        }
        runHist[idx].incrementAndGet();
    }

    /**
     * 本轮（自上次 reset 以来）全程的分位延迟，毫秒。
     *
     * <p>精度是 {@link #RUN_BUCKET_MS} 毫秒；落在溢出桶时返回真实最大值而不是桶边界，
     * 免得把「有个 3 秒的长尾」报成「200ms」。
     */
    public double runPercentileMs(double p) {
        long total = runCount.get();
        if (total == 0) {
            return 0;
        }
        long target = (long) Math.ceil(p * total);
        long acc = 0;
        for (int i = 0; i < runHist.length; i++) {
            acc += runHist[i].get();
            if (acc >= target) {
                return i == RUN_FINE_BUCKETS
                        ? runMaxNanos.get() / 1_000_000.0
                        : (i + 1) * RUN_BUCKET_MS;
            }
        }
        return runMaxNanos.get() / 1_000_000.0;
    }

    public double runMaxMs() {
        return runMaxNanos.get() / 1_000_000.0;
    }

    public long runSampleCount() {
        return runCount.get();
    }

    /** 由 /verify/reset 调用。不清零就会把上一轮的延迟混进这一轮的报告。 */
    public void resetRun() {
        for (AtomicLong b : runHist) {
            b.set(0);
        }
        runCount.set(0);
        runMaxNanos.set(0);
    }

    /** 当前滚动窗口的 P99（毫秒）。控制面的主输入。 */
    public double p99Millis() {
        HistogramSnapshot snapshot = latency.takeSnapshot();
        for (ValueAtPercentile v : snapshot.percentileValues()) {
            if (Math.abs(v.percentile() - 0.99) < 1e-9) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return snapshot.max(TimeUnit.MILLISECONDS);
    }

    public double successCount() {
        return success.count();
    }

    public double rejectedCount() {
        return rejected.count();
    }

    public double riskDroppedCount() {
        return riskDropped.count();
    }

    /**
     * 累计归还号源次数。
     *
     * <p>一致性校验器用它<b>判断自己的采样是否稳定</b>：归还是「先改 MySQL 状态、
     * 再还 Redis 号源」两步，中间那个窗口里预约已不占号而号还没回桶，
     * 此时读出来的号源守恒等式必然差几个。校验前后各读一次这个计数，
     * 变了就说明采样期间有归还在进行，读数不可信。
     */
    public double slotsReleasedCount() {
        return slotsReleased.count();
    }

    public double soldOutCount() {
        return soldOut.count();
    }

    public double duplicateCount() {
        return duplicate.count();
    }

    public double errorCount() {
        return error.count();
    }

    /** 所有走到业务逻辑的请求数（含被限流拒绝和被风控丢弃的），用来算各种比率的分母。 */
    public double totalCount() {
        return successCount() + rejectedCount() + riskDroppedCount()
                + soldOutCount() + duplicateCount() + errorCount();
    }
}
