package com.flashpilot.dataplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.dedupe.PurchaseDedupe;
import com.flashpilot.dataplane.limit.LocalTokenBucket;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.clinic.risk.RiskControlService;
import com.flashpilot.clinic.risk.RiskDecision;
import com.flashpilot.clinic.risk.SlowLane;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 数据面的编排入口。<b>这条路径上没有任何 LLM 调用，也没有任何分布式锁。</b>
 *
 * <p>四步：限流 → 判重 → 库存 → 发事件。其中前两步在 LOCAL 判重模式下完全不碰网络，
 * 第三步绝大多数情况只做一次内存 CAS，只有成交的那一小部分请求（受库存上限约束）
 * 才会真正打一次 Redis。这就是为什么十万级请求打下来 Redis 的压力其实很小。
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final LocalTokenBucket limiter;
    private final LocalSegmentManager segments;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final InstanceIdentity identity;
    private final FlashPilotProperties props;
    private final SeckillMetrics metrics;
    private final RiskControlService riskControl;
    private final SlowLane slowLane;
    private final PurchaseDedupe dedupe;

    public SeckillService(LocalTokenBucket limiter, LocalSegmentManager segments,
                          StockRedisRepository stockRedis, HotConfigService hotConfig,
                          InstanceIdentity identity, FlashPilotProperties props, SeckillMetrics metrics,
                          RiskControlService riskControl, SlowLane slowLane,
                          PurchaseDedupe dedupe) {
        this.slowLane = slowLane;
        this.dedupe = dedupe;
        this.limiter = limiter;
        this.segments = segments;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.identity = identity;
        this.props = props;
        this.metrics = metrics;
        this.riskControl = riskControl;
    }

    public SeckillOutcome seckill(long poolId, long holderId) {
        return seckill(poolId, holderId, null);
    }

    public SeckillOutcome seckill(long poolId, long holderId, String deviceId) {
        long startNanos = System.nanoTime();
        try {
            return doSeckill(poolId, holderId, deviceId);
        } catch (Exception e) {
            logFailureThrottled(poolId, holderId, e);
            metrics.error();
            return SeckillOutcome.INTERNAL_ERROR;
        } finally {
            metrics.record(System.nanoTime() - startNanos);
        }
    }

    // ---------- 失败日志限流 ----------
    //
    // P6 主从切换实验里，Redis 变成只读副本之后 16477 个请求全部失败，
    // 而原来这里是每个失败都 log.error(..., e) —— <b>打出了 16477 条完整堆栈</b>，
    // 加上续行占了日志的绝大部分。日志是同步 I/O，生产环境这么写会把磁盘写满、
    // 并且让本来只是「依赖不可用」的故障升级成「服务被自己的日志拖死」。
    //
    // 故障期间的日志放大是个经典陷阱：真正有信息量的是「第一条堆栈」和「累计了多少」，
    // 后面每一条的堆栈都完全一样，写一万遍不增加任何信息。
    // 所以这里：首条打完整堆栈，之后每 1000 条打一行聚合计数，异常类型变化时重新打一次堆栈。
    // 第一版这里用「异常类型 + root message」当去重键，结果日志从 3.4 万行涨到 111 万行 ——
    // 因为 Redis 的报错里带 Lua 脚本的 SHA 和行号：
    //   READONLY You can't write against a read only replica. script: 6b8f4637..., on @user_script:34.
    // 不同脚本 SHA 不同、行号不同，键一直在变，于是每条都被判成「新类型」并打完整堆栈。
    //
    // 教训：任何基于「消息内容」的去重都必须先归一化掉 ID / SHA / 行号 / 数字，
    // 并且要有一个<b>与键无关的硬性速率上限</b>兜底 —— 归一化总会漏掉某种变体，
    // 而兜底能保证「漏掉了也不会把日志打爆」。
    private static final long STACK_TRACE_MIN_INTERVAL_MS = 5000;

    private final java.util.concurrent.atomic.AtomicLong failureCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastStackTraceAt =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastFailureKind = null;

    private void logFailureThrottled(long poolId, long holderId, Exception e) {
        long n = failureCount.incrementAndGet();
        String kind = e.getClass().getSimpleName() + ":" + normalize(rootMessage(e));
        boolean kindChanged = !kind.equals(lastFailureKind);

        // 硬性上限：无论类型怎么变，5 秒内最多一条堆栈
        long now = System.currentTimeMillis();
        long last = lastStackTraceAt.get();
        boolean allowStack = (now - last) >= STACK_TRACE_MIN_INTERVAL_MS
                && lastStackTraceAt.compareAndSet(last, now);

        if (kindChanged) {
            lastFailureKind = kind;
            if (allowStack) {
                log.error("秒杀处理异常（累计 {} 次，类型：{}）poolId={} holderId={}", n, kind, poolId, holderId, e);
            } else {
                log.error("秒杀处理异常（累计 {} 次，类型：{}）—— 堆栈已限流", n, kind);
            }
        } else if (n % 2000 == 0) {
            log.error("秒杀处理异常累计 {} 次，类型仍为：{}", n, kind);
        }
    }

    /** 把 SHA、十六进制、数字都折叠掉，让同一类错误映射到同一个键。 */
    private static String normalize(String msg) {
        return msg == null ? "null"
                : msg.replaceAll("[0-9a-f]{8,}", "#").replaceAll("\\d+", "#");
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur.getCause() != null && depth++ < 6) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    private SeckillOutcome doSeckill(long poolId, long holderId, String deviceId) {
        // ① 风控。放在最前面是因为它最便宜（纯本地 CMS，零 I/O），
        // 应该在花任何其它成本之前就把明确的黑名单挡掉。
        RiskDecision risk = riskControl.judge(holderId, deviceId);
        if (risk == RiskDecision.BLOCK) {
            return SeckillOutcome.RISK_BLOCKED;
        }

        // ② 降权处置：走慢车道。
        //
        // 慢车道是一条<b>绝对速率很低的独立车道</b>，不是「多过一道主限流」——
        // 后者在低负载下完全没效果（主桶不饱和时连过两道和一道没区别），
        // 而号源是有限的：黄牛在平峰期刷走 40 个号，这些号就真的没了。
        // 详见 SlowLane 的类注释。
        //
        // 注意它仍然<b>不是硬拒</b>：降权用户每秒还能通过几个，
        // 因为误判一个真实患者（可能是急需就诊的老人）的代价远高于放过一个黄牛。
        //
        // 注意计的是 riskDropped 而<b>不是</b> rejected，返回码也是 4291 而不是 4290。
        // 原来两者共用，导致 AIMD 把风控的处置当成自己的容量信号一路砍限流
        // （实测 20000 → 6860，而真实原因跟容量毫无关系），
        // 压测报告也把风控丢弃显示成「限流拒绝」。详见 SeckillMetrics#riskDropped。
        if (risk == RiskDecision.DEMOTE && !slowLane.tryAcquire()) {
            metrics.riskDropped();
            return SeckillOutcome.RISK_DEMOTED;
        }

        // ③ 主限流。控制面调的就是这里的速率，是整个闭环的执行末端。
        if (!limiter.tryAcquire()) {
            metrics.rejected();
            return SeckillOutcome.RATE_LIMITED;
        }

        // ② 一人一单。放在扣库存之前，避免同一个人反复占用库存名额。
        if (!markFirstPurchase(poolId, holderId)) {
            metrics.duplicate();
            return SeckillOutcome.ALREADY_BOUGHT;
        }

        int prefIndex = bucketIndexOf(holderId);
        LocalSegmentManager.Reservation reservation = segments.reserveOne(poolId, prefIndex);

        switch (reservation) {
            case LOCAL_RESERVED -> {
                // ③ 本地预占成功，现在把它落实到 Redis：原子地发事件 + 租约扣 1
                StockRedisRepository.SellOne sell = stockRedis.sellOne(poolId, identity.id(), holderId);
                if (!sell.ok()) {
                    // Redis 说这个实例的租约持有量已经是 0 —— 状态不一致，属于 bug。
                    // 宁可让这个用户重试，也不能凭空多卖。
                    segments.onSellFailed(poolId);
                    rollbackFirstPurchase(poolId, holderId);
                    metrics.error();
                    return SeckillOutcome.INTERNAL_ERROR;
                }
                metrics.success();
                return SeckillOutcome.SUCCESS;
            }
            case USE_TAIL -> {
                // ④ 尾部模式：直接从桶里扣 1 件。慢一点，但保证最后几件卖得干净。
                StockRedisRepository.TailSell tail = stockRedis.sellOneTail(poolId, prefIndex, holderId);
                if (!tail.ok()) {
                    if (tail.remaining() <= 0) {
                        segments.markSoldOut(poolId);
                    }
                    rollbackFirstPurchase(poolId, holderId);
                    metrics.soldOut();
                    return SeckillOutcome.SOLD_OUT;
                }
                metrics.tailSale();
                metrics.success();
                return SeckillOutcome.SUCCESS;
            }
            default -> {
                rollbackFirstPurchase(poolId, holderId);
                metrics.soldOut();
                return SeckillOutcome.SOLD_OUT;
            }
        }
    }

    /**
     * 判重。两种模式的差别决定了本地号段到底能不能省掉那一次 RTT：
     * <ul>
     *   <li>{@code LOCAL} —— 内存判重，热路径零网络。<b>前提是网关按 holderId 粘性路由</b>，
     *       否则同一个用户落到不同实例上就判不出重（MySQL 唯一索引仍会兜住，
     *       但会白占一个库存名额再回滚）。</li>
     *   <li>{@code REDIS} —— 全局判重，不依赖路由，但每个请求多一次 RTT，
     *       本地号段省下来的那次 RTT 就白省了。</li>
     * </ul>
     * 这两种模式的对比本身就是一组实验，见 README。
     */
    private boolean markFirstPurchase(long poolId, long holderId) {
        return dedupe.mark(poolId, holderId);
    }

    /** 没抢到就必须把判重标记撤掉，否则用户被永久挡在门外。见 PurchaseDedupe#clear。 */
    private void rollbackFirstPurchase(long poolId, long holderId) {
        dedupe.clear(poolId, holderId);
    }

    private int bucketIndexOf(long holderId) {
        int active = Math.max(1, hotConfig.getInt(ConfigParam.ACTIVE_BUCKETS));
        return (int) Math.floorMod(holderId, active);
    }
}
