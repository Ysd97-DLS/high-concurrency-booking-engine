package com.flashpilot.dataplane.stock;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;

/**
 * 三层库存里的第三层：实例本地号段。
 *
 * <p>这是整个数据面性能的来源。绝大多数请求只做一次 {@code AtomicLong} 的 CAS，
 * 完全不碰网络；只有本地号段用尽时才去 Redis 领下一段（默认 20 件领一次，
 * 也就是 Redis 的调用量降到原来的 1/20）。
 *
 * <p><b>两个必须理解的细节：</b>
 * <ol>
 *   <li><b>为什么先扣本地再发事件</b>：本地 CAS 成功只是「预占」，随后由
 *       {@link StockRedisRepository#sellOne} 原子地发事件并把租约持有量减 1。
 *       如果实例在这两步之间崩了，内存里的预占随进程消失，而 Redis 里租约仍记着这一件，
 *       回收任务会把它还回桶 —— 不丢不多。反过来先发事件再扣本地就会超卖。</li>
 *   <li><b>尾部模式</b>：桶里剩余低于阈值时关闭号段，退化为单件直扣。
 *       否则活动尾声会出现「全局还剩 3 件但分别卡在 3 个实例手里」的少卖。</li>
 * </ol>
 */
@Component
public class LocalSegmentManager {

    private static final Logger log = LoggerFactory.getLogger(LocalSegmentManager.class);

    /** 领号段时最多等多久拿到 refill 锁；等不到就让这个请求走尾部路径，绝不阻塞住线程。 */
    private static final long REFILL_LOCK_WAIT_MS = 50;

    /**
     * 「已售罄」这个结论只缓存这么久。
     *
     * <p><b>这里有个必须加 TTL 的理由，不加就是 bug</b>：如果把 soldOut 缓存成永久的布尔值，
     * 那么当某个实例宕机、它手里的号段被租约回收还回桶之后，
     * 已经认定售罄的实例会永远拒绝售卖 —— 库存回来了却卖不出去，这就是少卖。
     * 所以售罄状态必须会过期、必须重新去 Redis 确认一次。
     */
    private static final long SOLD_OUT_CACHE_MS = 500;

    /**
     * 尾部模式的重新评估间隔。
     *
     * <p><b>尾部模式必须能退出。</b>原来它是单向闩锁：一旦桶余量跌破阈值就永久为 true，
     * 只有 preheat 的 {@code resetLocal} 能清掉。在原来的抽象秒杀域里这是对的 ——
     * 库存只减不增，进了尾部就不会再出来。
     *
     * <p><b>挂号域打破了这个前提</b>：退号、超时释放、对账补偿都会把号加回桶，
     * 而<b>分批放号</b>更是持续往里加 —— 而它恰好是这个垂类的招牌功能。
     * 实测：60 个号、阈值 50，抢掉 20 个后进尾部模式；再往桶里补 500 个号，
     * {@code tailMode} 仍是 true，后续 30 次抢号<b>领号段 0 次</b>，
     * 号段命中率从 1.0 掉到 0.5 —— 整套三层库存的性能优势没了，
     * 每次成交都退化成一次 Redis 往返，而正确性完全没问题、日志里也没有任何异常。
     *
     * <p>1 秒一次的重新评估：分批放号的批次间隔是 2 秒，所以最多晚一个批次恢复；
     * 而代价只是每个号池每秒一次只读的 {@code stats} 调用。
     */
    private static final long TAIL_RECHECK_MS = 1000;

    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final InstanceIdentity identity;
    private final FlashPilotProperties props;

    private final ConcurrentHashMap<Long, ItemState> states = new ConcurrentHashMap<>();

    // 埋点用的计数器
    private final AtomicLong localHits = new AtomicLong();
    private final AtomicLong refills = new AtomicLong();
    private final AtomicLong steals = new AtomicLong();
    private final AtomicLong anomalies = new AtomicLong();

    public LocalSegmentManager(StockRedisRepository stockRedis, HotConfigService hotConfig,
                               InstanceIdentity identity, FlashPilotProperties props) {
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.identity = identity;
        this.props = props;
    }

    /** 单个商品在本实例内的状态。 */
    static final class ItemState {
        final long poolId;
        final AtomicLong localRemaining = new AtomicLong(0);
        final ReentrantLock refillLock = new ReentrantLock();
        /** 本地判重集合（LOCAL 模式用）。要求网关按 holderId 粘性路由才正确。 */
        final Set<Long> boughtUsers = ConcurrentHashMap.newKeySet();
        volatile boolean tailMode = false;
        /** 下次允许重新评估尾部模式的时刻。用 CAS 推进，保证同一时间只有一个线程去查 Redis。 */
        final AtomicLong tailRecheckAt = new AtomicLong(0);
        /** 售罄结论的有效截止时间（毫秒时间戳）。见 {@link #SOLD_OUT_CACHE_MS} 上的说明。 */
        volatile long soldOutUntil = 0L;

        ItemState(long poolId) {
            this.poolId = poolId;
        }

        boolean believesSoldOut() {
            return soldOutUntil > System.currentTimeMillis();
        }
    }

    /** 本地预占的结果。 */
    public enum Reservation {
        /** 已从本地号段预占 1 件，调用方接着必须调 sellOne 落实到 Redis。 */
        LOCAL_RESERVED,
        /** 走尾部单件模式（或号段被降级开关关掉了）。 */
        USE_TAIL,
        /** 全局售罄。 */
        SOLD_OUT
    }

    ItemState state(long poolId) {
        return states.computeIfAbsent(poolId, ItemState::new);
    }

    public Collection<Long> knownItemIds() {
        return states.keySet();
    }

    // ---------- 判重 ----------

    /** LOCAL 模式判重：返回 true 表示第一次买。 */
    public boolean markBoughtLocal(long poolId, long holderId) {
        return state(poolId).boughtUsers.add(holderId);
    }

    public void unmarkBoughtLocal(long poolId, long holderId) {
        state(poolId).boughtUsers.remove(holderId);
    }

    // ---------- 核心：预占一件 ----------

    public Reservation reserveOne(long poolId, int prefIndex) {
        ItemState st = state(poolId);

        // 快路径：纯内存 CAS，零网络。
        //
        // <b>这一步必须在所有模式判断之前 —— 手里有货就先卖手里的。</b>
        //
        // 原来的顺序是「售罄缓存 → 号段开关 → 尾部模式 → takeLocal」，于是
        // 一旦进入尾部模式，实例<b>已经领到手的本地号段就再也卖不出去了</b>：
        // 尾部路径只读 Redis 桶，而那些号早已从桶里扣走、记在实例的租约名下。
        //
        // 实测：排班 40 个号、号段大小 20，抢 60 次只成功 21 次，
        // 剩下 <b>19 个号（47%）</b>躺在 localRemaining 里，请求却全部收到「号源已满」。
        // 这就是<b>少卖</b>，而号段租约机制存在的全部意义就是防少卖。
        //
        // 更棘手的是<b>等式③ 看不见它</b>：这 19 个号算在 leaseHeld 里，
        // 账目完全平衡（总数 = 桶 + 租约 + 占号 + 在途）。校验器只能看出
        // 「号现在在谁手里」，看不出「持有者根本不打算卖」。
        // <b>账目平衡不等于号源可售 —— 这是这套等式的一个固有盲区。</b>
        //
        // 放到最前面在任何模式下都正确：这些号已经出了桶，不卖就是纯浪费。
        // 而且它是有限的（排空一次就没了），不会妨碍降级开关的意图。
        if (takeLocal(st)) {
            localHits.incrementAndGet();
            return Reservation.LOCAL_RESERVED;
        }

        if (st.believesSoldOut()) {
            return Reservation.SOLD_OUT;
        }
        // 降级开关或尾部模式：不走号段
        if (!hotConfig.getBool(ConfigParam.SEGMENT_ENABLED)) {
            return Reservation.USE_TAIL;
        }
        // 尾部模式不是终态：库存可能被退号 / 超时释放 / 分批放号 / 对账补偿加回来。
        // 所以定期重新评估一次，能恢复就恢复。见 TAIL_RECHECK_MS 的说明。
        if (st.tailMode && !tryLeaveTailMode(st)) {
            return Reservation.USE_TAIL;
        }
        // 慢路径：领下一个号段
        if (!refill(st, prefIndex)) {
            return st.believesSoldOut() ? Reservation.SOLD_OUT : Reservation.USE_TAIL;
        }
        if (takeLocal(st)) {
            localHits.incrementAndGet();
            return Reservation.LOCAL_RESERVED;
        }
        // 领到了但又被别的线程抢空 —— 极小概率，退到尾部路径而不是自旋
        return Reservation.USE_TAIL;
    }

    /** 预占之后 Redis 落实失败时调用：强制下次重新领号段，并记一次异常。 */
    /**
     * 尝试退出尾部模式。
     *
     * <p>用<b>只读</b>的 {@code stats} 判断，而不是直接去领一个号段：
     * 领号段会在余量仍然不足时留下一个「尾部模式中却持有本地号段」的中间状态，
     * 那个号段在下次重评估之前卖不出去，得等下线才归还。只读判断没有这个副作用。
     *
     * <p>CAS 推进 {@code tailRecheckAt} 保证同一个评估窗口内只有一个线程真的查 Redis，
     * 其余线程直接走尾部路径 —— 否则高并发下每个请求都会打一次 stats，
     * 那就把「省掉 Redis 往返」彻底做反了。
     *
     * @return true 表示已退出尾部模式，调用方可以继续走号段路径
     */
    private boolean tryLeaveTailMode(ItemState st) {
        long now = System.currentTimeMillis();
        long due = st.tailRecheckAt.get();
        if (now < due || !st.tailRecheckAt.compareAndSet(due, now + TAIL_RECHECK_MS)) {
            return false;
        }
        try {
            int tail = hotConfig.getInt(ConfigParam.TAIL_THRESHOLD);
            int bucketSum = stockRedis.stats(st.poolId).bucketSum();
            if (bucketSum > tail) {
                st.tailMode = false;
                st.soldOutUntil = 0L;      // 库存回来了，之前的"售罄"判断也该失效
                log.info("退出尾部单件模式 poolId={} 桶剩余={} 阈值={} —— 库存已回补（退号/放号/对账）",
                        st.poolId, bucketSum, tail);
                return true;
            }
        } catch (Exception e) {
            // 查不到就维持现状。尾部模式是安全的降级态，宁可慢也不能错。
            log.debug("尾部模式重评估失败 poolId={}：{}", st.poolId, e.toString());
        }
        return false;
    }

    public void onSellFailed(long poolId) {
        ItemState st = state(poolId);
        st.localRemaining.set(0);
        anomalies.incrementAndGet();
        log.warn("租约持有量与本地预占不一致 poolId={} instance={}，已清空本地余量强制重领",
                poolId, identity.id());
    }

    /** 后续步骤（如判重回滚）需要把预占还回本地时用。 */
    public void giveBackLocal(long poolId) {
        state(poolId).localRemaining.incrementAndGet();
    }

    private boolean takeLocal(ItemState st) {
        while (true) {
            long cur = st.localRemaining.get();
            if (cur <= 0) {
                return false;
            }
            if (st.localRemaining.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }

    /**
     * 领一个号段。只允许一个线程去 Redis，其它线程等一小会儿就走尾部路径，
     * 避免号段用尽的瞬间成百上千个线程同时打 Redis（这就是缓存击穿的同一个套路）。
     */
    private boolean refill(ItemState st, int prefIndex) {
        boolean locked;
        try {
            locked = st.refillLock.tryLock(REFILL_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            // 没抢到锁，但别的线程可能已经领到了，再看一眼
            return st.localRemaining.get() > 0;
        }
        try {
            if (st.localRemaining.get() > 0) {
                return true;
            }
            if (st.believesSoldOut() || st.tailMode) {
                return false;
            }
            int need = hotConfig.getInt(ConfigParam.SEGMENT_SIZE);
            long expireAt = System.currentTimeMillis() + props.stock().leaseTtlMs();
            StockRedisRepository.SegmentTake take =
                    stockRedis.takeSegment(st.poolId, need, prefIndex, identity.id(), expireAt);
            refills.incrementAndGet();
            if (take.stolen()) {
                steals.incrementAndGet();
            }

            if (take.got() > 0) {
                st.localRemaining.addAndGet(take.got());
            }

            // 桶里剩得不多了 → 进尾部模式，把剩下的货交给单件直扣，保证卖得干净。
            // 这里用「桶剩余」而不是「全局剩余」是刻意的：偏保守，宁可早一点进尾部模式。
            int tail = hotConfig.getInt(ConfigParam.TAIL_THRESHOLD);
            if (take.remaining() <= tail) {
                if (!st.tailMode) {
                    log.info("进入尾部单件模式 poolId={} 桶剩余={} 阈值={}", st.poolId, take.remaining(), tail);
                }
                st.tailMode = true;
                // 记下重评估时刻，避免刚进尾部就被下一个请求立刻拉出来
                st.tailRecheckAt.set(System.currentTimeMillis() + TAIL_RECHECK_MS);
            }
            if (take.got() == 0 && take.remaining() == 0) {
                // 桶空 + 领不到，但别的实例手里可能还有货，所以不能直接判死。
                // 交给尾部模式去打 Redis 确认，那条路径才是权威。
                st.tailMode = true;
                return false;
            }
            return take.got() > 0;
        } finally {
            st.refillLock.unlock();
        }
    }

    /**
     * 尾部模式确认全局售罄后调用，避免后续请求继续打 Redis。
     *
     * <p>注意这是个<b>有效期只有几百毫秒的缓存</b>而不是终态：租约回收可能把库存还回桶，
     * 缓存过期后本实例会重新去 Redis 确认一次。
     */
    public void markSoldOut(long poolId) {
        state(poolId).soldOutUntil = System.currentTimeMillis() + SOLD_OUT_CACHE_MS;
    }

    // ---------- 生命周期 ----------

    /**
     * 优雅下线：把本地没卖完的号段还回桶。
     * 这一步覆盖绝大多数「正常发布」场景；{@code kill -9} 才需要靠租约过期回收兜底。
     */
    @PreDestroy
    public void returnAllOnShutdown() {
        for (ItemState st : states.values()) {
            long left = st.localRemaining.getAndSet(0);
            if (left <= 0) {
                continue;
            }
            try {
                int returned = stockRedis.returnSegment(st.poolId, identity.id(), (int) left);
                log.info("优雅下线归还库存 poolId={} 本地余量={} 实际归还={}", st.poolId, left, returned);
            } catch (Exception e) {
                log.error("归还库存失败 poolId={} 余量={}，将由租约过期回收兜底：{}",
                        st.poolId, left, e.toString());
            }
        }
    }

    /** 压测前重置本实例的本地状态。 */
    public void resetLocal(long poolId) {
        ItemState st = state(poolId);
        st.localRemaining.set(0);
        st.tailMode = false;
        st.tailRecheckAt.set(0);
        st.soldOutUntil = 0L;
        st.boughtUsers.clear();
        localHits.set(0);
        refills.set(0);
        steals.set(0);
        anomalies.set(0);
        log.info("本地号段状态已重置 poolId={}", poolId);
    }

    // ---------- 埋点 ----------

    public long localHits() {
        return localHits.get();
    }

    public long refills() {
        return refills.get();
    }

    public long steals() {
        return steals.get();
    }

    public long anomalies() {
        return anomalies.get();
    }

    /** 号段命中率：直接衡量「Redis 被卸载了多少」。 */
    public double segmentHitRatio() {
        long hits = localHits.get();
        long total = hits + refills.get();
        return total == 0 ? 1.0 : (double) hits / total;
    }

    public long localRemaining(long poolId) {
        return state(poolId).localRemaining.get();
    }

    public boolean tailMode(long poolId) {
        return state(poolId).tailMode;
    }
}
