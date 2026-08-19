package com.flashpilot.controlplane;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.dataplane.stream.ConsumerStats;
import com.flashpilot.metrics.SeckillMetrics;
import com.flashpilot.verify.ExperimentContext;

/**
 * 控制面的「眼睛」：每秒把散落各处的指标聚合成一个 {@link MetricsSnapshot}。
 *
 * <p>为什么要单独一层而不是让 L0/L1 各自去读：
 * <ol>
 *   <li>把 Redis 读取集中到一处，L0（1 秒一次）和 L1（事件驱动）共享同一份快照，不重复打 Redis；</li>
 *   <li>Prometheus 的 Gauge 只读内存里的值，scrape 时不会阻塞在网络上；</li>
 *   <li>QPS 和误拒率必须用<b>窗口增量</b>算，累计值会把抖动摊平——这个计算逻辑只该有一份。</li>
 * </ol>
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final SeckillMetrics metrics;
    private final StockRedisRepository stockRedis;
    private final LocalSegmentManager segments;
    private final ConsumerStats consumerStats;
    private final HotConfigService hotConfig;
    private final StringRedisTemplate redis;
    private final FlashPilotProperties props;
    private final ExperimentContext experiment;

    private final AtomicReference<MetricsSnapshot> latest = new AtomicReference<>(MetricsSnapshot.empty());

    // 上一轮的累计值，用来算窗口增量
    private double prevSuccess;
    private double prevTotal;
    private double prevRejected;
    private double prevRiskDropped;
    private long prevTimestamp = System.currentTimeMillis();

    public MetricsCollector(SeckillMetrics metrics, StockRedisRepository stockRedis,
                            LocalSegmentManager segments, ConsumerStats consumerStats,
                            HotConfigService hotConfig, StringRedisTemplate redis,
                            FlashPilotProperties props, ExperimentContext experiment) {
        this.metrics = metrics;
        this.stockRedis = stockRedis;
        this.segments = segments;
        this.consumerStats = consumerStats;
        this.hotConfig = hotConfig;
        this.redis = redis;
        this.props = props;
        this.experiment = experiment;
    }

    public MetricsSnapshot latest() {
        return latest.get();
    }

    @Scheduled(fixedDelayString = "1000")
    public void collect() {
        try {
            latest.set(build());
        } catch (Exception e) {
            log.debug("指标采集失败：{}", e.toString());
        }
    }

    private MetricsSnapshot build() {
        long now = System.currentTimeMillis();
        double windowSeconds = Math.max(0.001, (now - prevTimestamp) / 1000.0);

        double success = metrics.successCount();
        double total = metrics.totalCount();
        double rejected = metrics.rejectedCount();
        double riskDropped = metrics.riskDroppedCount();

        double effectiveQps = Math.max(0, success - prevSuccess) / windowSeconds;
        double requestQps = Math.max(0, total - prevTotal) / windowSeconds;
        double rejectedDelta = Math.max(0, rejected - prevRejected);
        double riskDroppedDelta = Math.max(0, riskDropped - prevRiskDropped);
        double totalDelta = Math.max(0, total - prevTotal);
        // 两个比率分开算。rejectRate 只含主限流 —— 这是控制面唯一能影响的那部分，
        // 混进风控丢弃会让 AIMD 为了"降低误拒"去调一个对此无效的旋钮，一路砍到底。
        double rejectRate = totalDelta <= 0 ? 0 : rejectedDelta / totalDelta;
        double riskDropRate = totalDelta <= 0 ? 0 : riskDroppedDelta / totalDelta;

        prevSuccess = success;
        prevTotal = total;
        prevRejected = rejected;
        prevRiskDropped = riskDropped;
        prevTimestamp = now;

        long poolId = experiment.poolId();
        int activeBuckets = hotConfig.getInt(ConfigParam.ACTIVE_BUCKETS);
        StockRedisRepository.Stats stats = stockRedis.stats(poolId);
        double skew = stats.skew(activeBuckets);

        long pending = readPending();
        long streamLength = safeStreamLength();

        long limitQps = Math.round(hotConfig.get(ConfigParam.LIMIT_QPS));
        long configVersion = safeVersion();

        // 刷新 Prometheus 的 Gauge
        metrics.updateStock(stats.bucketSum(), stats.leaseHeld(), skew);
        metrics.updateStream(pending, streamLength);
        metrics.updateControl(limitQps, configVersion);

        return new MetricsSnapshot(
                now, windowSeconds,
                effectiveQps, requestQps, rejectRate, riskDropRate, metrics.p99Millis(),
                stats.bucketSum(), stats.leaseHeld(), skew, activeBuckets, segments.tailMode(poolId),
                segments.segmentHitRatio(), segments.refills(), segments.steals(), segments.anomalies(),
                pending, streamLength,
                consumerStats.consumedCount(), consumerStats.duplicateCount(),
                consumerStats.deadLetterCount(), consumerStats.oversoldBlockedCount(),
                limitQps, configVersion,
                metrics.runPercentileMs(0.50), metrics.runPercentileMs(0.95),
                metrics.runPercentileMs(0.99), metrics.runMaxMs(), metrics.runSampleCount());
    }

    private long readPending() {
        try {
            PendingMessagesSummary summary =
                    redis.opsForStream().pending(props.stream().key(), props.stream().group());
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (Exception e) {
            // 消费组还没建好时会抛异常，不是问题
            return 0L;
        }
    }

    private long safeStreamLength() {
        try {
            return stockRedis.streamLength();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long safeVersion() {
        try {
            return hotConfig.version();
        } catch (Exception e) {
            return 0L;
        }
    }
}
