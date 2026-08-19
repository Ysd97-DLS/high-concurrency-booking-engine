package com.flashpilot.dataplane.stock;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.config.LuaScripts;
import com.flashpilot.config.Replies;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;

/**
 * 库存的 Redis 侧操作，全部通过 Lua 脚本完成。
 *
 * <p>这个类不做任何业务判断，只负责把脚本调对、把返回值翻译成 Java 类型。
 * 业务编排在 {@link com.flashpilot.dataplane.SeckillService}，
 * 本地号段的管理在 {@link LocalSegmentManager}。
 */
@Repository
public class StockRedisRepository {

    private static final Logger log = LoggerFactory.getLogger(StockRedisRepository.class);

    private final StringRedisTemplate redis;
    private final LuaScripts scripts;
    private final String streamKey;
    private final String streamGroup;
    private final HotConfigService hotConfig;

    public StockRedisRepository(StringRedisTemplate redis, LuaScripts scripts, FlashPilotProperties props,
                                HotConfigService hotConfig) {
        this.hotConfig = hotConfig;
        this.redis = redis;
        this.scripts = scripts;
        this.streamKey = props.stream().key();
        this.streamGroup = props.stream().group();
    }

    /** 领号段的结果。 */
    public record SegmentTake(int got, boolean stolen, int remaining) {
        public boolean empty() {
            return got <= 0;
        }
    }

    /** 卖出一件的结果。{@code ok=false} 且 streamId 为空表示租约与本地状态不一致（属于 bug）。 */
    public record SellOne(boolean ok, String streamId) {
    }

    /** 尾部模式卖出一件的结果。 */
    public record TailSell(boolean ok, String streamId, int remaining) {
    }

    /** 租约回收结果。 */
    public record Reclaim(int reclaimed, int instances) {
        public boolean any() {
            return reclaimed > 0 || instances > 0;
        }
    }

    /** 库存全局快照。 */
    public record Stats(int bucketSum, int leaseHeld, int instances, int[] buckets) {
        public int total() {
            return bucketSum + leaseHeld;
        }

        /** 桶倾斜度 (max-min)/mean，只统计活跃桶。控制面用它判断要不要调桶数。 */
        public double skew(int activeBuckets) {
            int n = Math.min(activeBuckets, buckets.length);
            if (n <= 0) {
                return 0;
            }
            int min = Integer.MAX_VALUE, max = 0, sum = 0;
            for (int i = 0; i < n; i++) {
                min = Math.min(min, buckets[i]);
                max = Math.max(max, buckets[i]);
                sum += buckets[i];
            }
            double mean = (double) sum / n;
            return mean <= 0 ? 0 : (max - min) / mean;
        }
    }

    // ---------- 预热 / 重置 ----------

    /**
     * 把总库存切分到活跃桶里，并清掉上一轮的租约与判重位图。
     * 压测前必须调用，否则各轮实验的起点不一致。
     */
    public void preheat(long poolId, int total, int activeBuckets) {
        List<String> toDelete = new ArrayList<>(StockKeys.allBuckets(poolId));
        toDelete.add(StockKeys.lease(poolId));
        toDelete.add(StockKeys.bought(poolId));
        redis.delete(toDelete);

        int n = Math.max(1, Math.min(activeBuckets, StockKeys.MAX_BUCKETS));
        int base = total / n;
        int remainder = total % n;
        for (int i = 0; i < n; i++) {
            int amount = base + (i < remainder ? 1 : 0);
            redis.opsForValue().set(StockKeys.bucket(poolId, i), String.valueOf(amount));
        }
        redis.opsForHash().put(StockKeys.meta(poolId), "total", String.valueOf(total));
        log.info("预热完成 poolId={} total={} activeBuckets={} 每桶≈{}", poolId, total, n, base);
    }

    public int metaTotal(long poolId) {
        Object v = redis.opsForHash().get(StockKeys.meta(poolId), "total");
        return v == null ? 0 : (int) Replies.asLong(v);
    }

    // ---------- 号段 ----------

    public SegmentTake takeSegment(long poolId, int need, int prefIndex, String instanceId, long leaseExpireAtMs) {
        List<?> reply = redis.execute(scripts.takeSegment,
                StockKeys.bucketsAndLease(poolId),
                String.valueOf(need),
                String.valueOf(prefIndex),
                instanceId,
                String.valueOf(leaseExpireAtMs),
                String.valueOf(StockKeys.MAX_BUCKETS));
        if (reply == null) {
            return new SegmentTake(0, false, 0);
        }
        return new SegmentTake(
                Replies.asInt(reply, 0),
                Replies.asLong(reply, 1) == 1L,
                Replies.asInt(reply, 2));
    }

    public SellOne sellOne(long poolId, String instanceId, long holderId) {
        List<?> reply = redis.execute(scripts.sellOne,
                List.of(streamKey, StockKeys.lease(poolId)),
                instanceId,
                String.valueOf(holderId),
                String.valueOf(poolId),
                String.valueOf(System.currentTimeMillis()));
        if (reply == null) {
            return new SellOne(false, null);
        }
        return new SellOne(Replies.asLong(reply, 0) == 1L, Replies.asString(reply, 1));
    }

    public TailSell sellOneTail(long poolId, int prefIndex, long holderId) {
        List<?> reply = redis.execute(scripts.sellOneTail,
                StockKeys.bucketsAndStream(poolId, streamKey),
                String.valueOf(StockKeys.MAX_BUCKETS),
                String.valueOf(prefIndex),
                String.valueOf(holderId),
                String.valueOf(poolId),
                String.valueOf(System.currentTimeMillis()));
        if (reply == null) {
            return new TailSell(false, null, 0);
        }
        return new TailSell(
                Replies.asLong(reply, 0) == 1L,
                Replies.asString(reply, 1),
                Replies.asInt(reply, 2));
    }

    /**
     * 当前活跃桶数，钳制到 [1, MAX_BUCKETS]。
     *
     * <p><b>「归还只落在活跃桶内」这条规则由本类统一执行，不再让调用方各自传参。</b>
     * 原来三条归还路径各自决定传什么，结果只有一条传对了：
     * {@code releaseSlots} 传活跃桶数，而 {@code returnSegment} 和 {@code reclaimExpired}
     * 传的是 MAX_BUCKETS —— 于是每次实例归还或租约回收，号都落进非活跃桶。
     * 实测 8 活跃桶时回收 17 个号，17 个全进了物理桶 8，直连请求永远命不中。
     *
     * <p>根因有两层：Lua 侧一个参数兼任「KEYS 下标」和「扫描范围」（已拆开），
     * Java 侧则是<b>把一条不变量交给三个调用方各自记住</b>。
     * 后者是更本质的那层 —— 规则应该由拥有它的那个类强制执行，
     * 而不是写在注释里希望每个调用者都读到。
     */
    private int activeBuckets() {
        int v = hotConfig.getInt(ConfigParam.ACTIVE_BUCKETS);
        return Math.max(1, Math.min(v, StockKeys.MAX_BUCKETS));
    }

    /** 优雅归还，返回实际归还数量（以租约记录为上限，防止重复归还造成超卖）。 */
    public int returnSegment(long poolId, String instanceId, int amount) {
        Long returned = redis.execute(scripts.returnSegment,
                StockKeys.bucketsAndLease(poolId),
                String.valueOf(StockKeys.MAX_BUCKETS),   // KEYS 布局用物理桶数
                instanceId,
                String.valueOf(amount),
                String.valueOf(activeBuckets()));        // 归还范围用活跃桶数
        return returned == null ? 0 : returned.intValue();
    }

    /**
     * 把已卖出的号源还回号池（预约取消 / 超时未支付）。
     *
     * <p>和 {@link #returnSegment} 的语义差别是这个升级里最容易搞错的一处：
     * {@code returnSegment} 是「实例把没卖完的本地号段还回去」，要同时扣该实例的租约持有量；
     * 这里是「已经卖出去的号又被取消了」，号源在引擎账上早已从实例转移出去，
     * <b>没有任何租约需要扣</b>。误用 returnSegment 会把某个实例的租约扣成负数，等式③ 立刻不平。
     *
     * @return 实际归还量
     */
    public int releaseSlots(long poolId, int amount) {
        // KEYS 传全部 32 个物理桶（沿用全局约定），但 ARGV[1] 传的是<b>活跃桶数</b>，
        // 归还只落在活跃范围内。
        //
        // 这里「取」和「还」的约定必须不一样，值得说清楚：
        // take_segment 的借调循环扫全部 32 个物理桶，所以取的时候传 MAX_BUCKETS 是安全的
        // —— 任何桶里的号都捞得到。但还的时候如果落到非活跃桶，就只有借调路径能碰到它，
        // 直连请求（prefIndex = holderId % activeBuckets）永远命不中，号源等于半失联。
        // 这正是第 5 号 bug（物理桶数随配置变化导致库存失联）的形态，不能在新代码里重犯。
        // 按量选策略，这个分支是实测逼出来的：
        //
        // release_slots.lua 的策略是「挑余量最少的活跃桶，把 amount 全加进去」。
        // 对单个号的归还（退号、超时释放）那是最优的 —— 每次一个，自然流向最空的桶。
        // 但**放号**走的是同一个方法，一次几十到几万个，于是整批全进一个桶：
        // 实测排班 20006 放 50 个号后桶分布是 45/5/0/0/0/0/0/0，桶倾斜度 8.000。
        //
        // 功能上没坏（号段 + 借调兜住了，16 次抢号全成功、只借调 1 次），
        // 但**桶分片的意图被架空**：直连命中只剩 1/activeBuckets，其余全靠借调，
        // 而号池一大那个独苗桶就成了所有借调的写热点 —— 分桶本来就是为了消除热点。
        //
        // 阈值取活跃桶数：批量大到每个桶都能分到，就该均摊；比桶数还少就挑最空的那个，
        // 那本来就是最优解，没必要多读 n 次 GET。
        int active = activeBuckets();
        RedisScript<List> script = amount >= active ? scripts.spreadSlots : scripts.releaseSlots;
        List<?> reply = redis.execute(script,
                StockKeys.allBuckets(poolId),
                String.valueOf(active),
                String.valueOf(amount));
        return reply == null ? 0 : Replies.asInt(reply, 0);
    }

    /** 只续期，不领新号段。心跳调用。 */
    public void renewLease(long poolId, String instanceId, long expireAtMs) {
        redis.opsForHash().put(StockKeys.lease(poolId), "e:" + instanceId, String.valueOf(expireAtMs));
    }

    public Reclaim reclaimExpired(long poolId) {
        List<?> reply = redis.execute(scripts.reclaimLeases,
                StockKeys.bucketsAndLease(poolId),
                String.valueOf(StockKeys.MAX_BUCKETS),   // KEYS 布局用物理桶数
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(activeBuckets()));        // 归还范围用活跃桶数
        if (reply == null) {
            return new Reclaim(0, 0);
        }
        return new Reclaim(Replies.asInt(reply, 0), Replies.asInt(reply, 1));
    }

    public Stats stats(long poolId) {
        List<?> reply = redis.execute(scripts.stats,
                StockKeys.bucketsAndLease(poolId),
                String.valueOf(StockKeys.MAX_BUCKETS));
        if (reply == null) {
            return new Stats(0, 0, 0, new int[0]);
        }
        int[] buckets = new int[Math.max(0, reply.size() - 3)];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = Replies.asInt(reply, i + 3);
        }
        return new Stats(Replies.asInt(reply, 0), Replies.asInt(reply, 1), Replies.asInt(reply, 2), buckets);
    }

    // ---------- 判重（REDIS 模式） ----------

    /**
     * 全局判重。{@code SETBIT} 会返回该位<i>原来</i>的值，所以一次命令就能完成
     * 「判断 + 标记」，不需要 Lua。
     *
     * @return true 表示这是该用户的第一次（可以继续），false 表示已经买过
     */
    public boolean markBought(long poolId, long holderId) {
        Boolean old = redis.opsForValue().setBit(StockKeys.bought(poolId), holderId, true);
        return old == null || !old;
    }

    /** 判重回滚：后续步骤失败时把位清掉，否则用户会被永久挡住。 */
    public void unmarkBought(long poolId, long holderId) {
        redis.opsForValue().setBit(StockKeys.bought(poolId), holderId, false);
    }

    public long streamLength() {
        Long len = redis.opsForStream().size(streamKey);
        return len == null ? 0L : len;
    }

    /**
     * 消费组里「已读但未 ACK」的消息数，即真正意义上的未处理量。
     *
     * <p>为什么一致性校验要用这个值，而不是用 {@code streamLen - 已处理数} 算出来：
     * 后者在消息被<b>重复处理</b>时会算出负数。而重复处理是会真实发生的 ——
     * 消息在落库队列里排队时还没 ACK，一旦排队时间超过 claim-idle 阈值，
     * {@code claimIdlePending} 就会把它抢回去再处理一遍。
     * 唯一索引保证了数据不会错（重复的那次被挡掉并计为 duplicate），
     * 但「已处理数」会因此大于 streamLen，减出来就是负值。
     *
     * <p>XPENDING 是 Redis 侧的事实，不受这种重复计数影响。
     */
    public long streamPending() {
        try {
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                    redis.opsForStream().pending(streamKey, streamGroup);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (Exception e) {
            log.warn("读 XPENDING 失败，按 0 处理：{}", e.toString());
            return 0L;
        }
    }

    /**
     * 清空 stream 但<b>保留消费组</b>。仅用于实验重置。
     *
     * <p>为什么必须清：一致性校验的等式 ③④ 都依赖 {@code XLEN} 表示「本轮发出的成交事件数」，
     * 上一轮的残留会让所有等式失真。
     *
     * <p>为什么不用 {@code DEL}：消费者容器正在轮询这个 key，删掉会让它收到 NOGROUP，
     * 而监听容器在某些错误下会直接取消订阅 —— 于是消费者悄悄停了，你还以为它在跑。
     * 用 {@code XTRIM MAXLEN 0} 就没有这个问题。
     */
    public void resetStreamAndGroup() {
        Long acked = redis.execute(scripts.resetStream, List.of(streamKey), streamGroup);
        log.info("Stream 已清空（保留消费组）stream={} group={} 顺带 ACK 掉残留 pending {} 条",
                streamKey, streamGroup, acked == null ? 0 : acked);
    }
}
