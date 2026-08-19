package com.flashpilot.controlplane.config;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flashpilot.config.FlashPilotProperties;

/**
 * 热配置中心。数据面读参数<b>只能</b>走这里，不要去读 {@link FlashPilotProperties}，
 * 否则控制面调了参数你的代码看不见。
 *
 * <p>三个机制配合：
 * <ol>
 *   <li><b>Redis Hash</b> 存权威值，多实例共享；</li>
 *   <li><b>Pub/Sub</b> 通知各实例立刻刷新，做到「秒级下发」；</li>
 *   <li><b>定时兜底轮询</b>——因为 <i>Redis 的 Pub/Sub 是 fire-and-forget，不保证送达</i>：
 *       订阅方断线重连期间的消息就丢了。所以必须有一条兜底路径，
 *       这也是面试里问「你怎么保证配置一定生效」的答案。</li>
 * </ol>
 * 热路径上读的是本地 {@code ConcurrentHashMap}，零 Redis 调用。
 */
@Service
public class HotConfigService {

    private static final Logger log = LoggerFactory.getLogger(HotConfigService.class);

    public static final String HASH_KEY = "fp:config";
    public static final String VERSION_KEY = "fp:config:version";
    public static final String CHANNEL = "fp:config:changed";

    private final StringRedisTemplate redis;
    private final FlashPilotProperties props;
    private final ConfigAuditRepository audit;

    private final Map<ConfigParam, Double> cache = new ConcurrentHashMap<>();

    public HotConfigService(StringRedisTemplate redis, FlashPilotProperties props, ConfigAuditRepository audit) {
        this.redis = redis;
        this.props = props;
        this.audit = audit;
    }

    @PostConstruct
    public void init() {
        // 本地先兜住默认值，保证即使 Redis 暂时不可用也不会读到 0
        cache.putAll(defaults());
        try {
            seedMissing();
            reloadFromRedis();
        } catch (Exception e) {
            log.warn("热配置初始化失败，暂时使用本地默认值：{}", e.toString());
        }
        log.info("热配置就绪 version={} values={}", version(), snapshot());
    }

    private Map<ConfigParam, Double> defaults() {
        Map<ConfigParam, Double> d = new EnumMap<>(ConfigParam.class);
        d.put(ConfigParam.LIMIT_QPS, (double) props.limit().permitsPerSecond());
        d.put(ConfigParam.ACTIVE_BUCKETS, (double) props.stock().bucketCount());
        d.put(ConfigParam.SEGMENT_SIZE, (double) props.stock().segmentSize());
        d.put(ConfigParam.TAIL_THRESHOLD, (double) props.stock().tailThreshold());
        d.put(ConfigParam.SEGMENT_ENABLED, 1.0);
        // 新参数必须在这里登记默认值，否则 get() 会回退到 param.min()。
        // 那样不但取值不合理，而且它们不会出现在 /control/config 里 ——
        // 控制面（含 Agent）看不见的参数等于不存在，也就永远不会被调。
        d.put(ConfigParam.RISK_THRESHOLD, (double) props.clinic().riskThreshold());
        d.put(ConfigParam.RELEASE_SPREAD_SECONDS, 0.0);
        d.put(ConfigParam.SLOW_LANE_QPS, 20.0);
        return d;
    }

    /** 只填缺失的键，不覆盖已有值——重启不该把控制面调好的参数打回原形。 */
    private void seedMissing() {
        Map<ConfigParam, Double> d = defaults();
        for (Map.Entry<ConfigParam, Double> e : d.entrySet()) {
            redis.opsForHash().putIfAbsent(HASH_KEY, e.getKey().key(), fmt(e.getValue()));
        }
        redis.opsForValue().setIfAbsent(VERSION_KEY, "1");
    }

    public void reloadFromRedis() {
        Map<Object, Object> raw = redis.opsForHash().entries(HASH_KEY);
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            ConfigParam.byKey(String.valueOf(e.getKey())).ifPresent(p -> {
                try {
                    cache.put(p, p.clamp(Double.parseDouble(String.valueOf(e.getValue()))));
                } catch (NumberFormatException ignored) {
                    // 手工往 Redis 里写了非法值，忽略并保留当前值
                }
            });
        }
    }

    /**
     * Pub/Sub 的兜底轮询。1 秒一次，配合 Pub/Sub 基本等于实时，
     * 而且在订阅连接断掉时仍然能收敛。
     */
    @Scheduled(fixedDelayString = "1000")
    public void periodicReload() {
        try {
            reloadFromRedis();
        } catch (Exception e) {
            log.debug("热配置轮询刷新失败：{}", e.toString());
        }
    }

    // ---------- 读 ----------

    public double get(ConfigParam param) {
        Double v = cache.get(param);
        return v != null ? v : param.clamp(defaults().getOrDefault(param, param.min()));
    }

    public int getInt(ConfigParam param) {
        return (int) Math.round(get(param));
    }

    public boolean getBool(ConfigParam param) {
        return get(param) >= 0.5;
    }

    public long version() {
        String v = redis.opsForValue().get(VERSION_KEY);
        return v == null ? 0L : Long.parseLong(v);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ConfigParam p : ConfigParam.values()) {
            double v = get(p);
            m.put(p.key(), v == Math.rint(v) ? (Object) (long) v : v);
        }
        return m;
    }

    // ---------- 写 ----------

    /**
     * 真正落库的写入口。<b>不要直接调它</b>——所有变更都应该先过
     * {@link com.flashpilot.controlplane.guard.GuardRail}，由护栏调用这里。
     *
     * @return 变更后的配置版本号
     */
    public long apply(ConfigParam param, double newValue, String source, String reason, String guardNote) {
        double old = get(param);
        double clamped = param.clamp(newValue);
        redis.opsForHash().put(HASH_KEY, param.key(), fmt(clamped));
        cache.put(param, clamped);
        Long version = redis.opsForValue().increment(VERSION_KEY);
        long v = version == null ? 0L : version;

        audit.record(v, param.key(), fmt(old), fmt(clamped), source, reason, true, guardNote);
        // 通知其它实例立刻刷新
        redis.convertAndSend(CHANNEL, String.valueOf(v));
        log.info("[控制面] {} {} -> {} (source={}, reason={})", param.key(), fmt(old), fmt(clamped), source, reason);
        return v;
    }

    /**
     * 记录一次被驳回的提案。审计表里留下驳回痕迹，本身就是护栏有效的证据。
     *
     * <p><b>被驳回的记录也要带上当前值。</b>原来 oldValue 硬写成 null，于是审计里只有
     * 「Agent 想把 limit.qps 改成 29400」——<b>看不出这是激进还是保守</b>：
     * 从 28000 改到 29400 是微调，从 300 改到 29400 是一步冲顶，
     * 两者对「这个 Agent 靠不靠谱」的判断完全相反。
     * 而审计的用途恰恰是事后判断控制面的行为模式。
     */
    public void recordRejected(String param, Double attempted, String source, String reason, String guardNote) {
        // 不在白名单的键名读不到当前值，此时留 null 是诚实的（确实没有"当前值"这回事）
        String currentText = ConfigParam.byKey(param).map(p -> fmt(get(p))).orElse(null);
        audit.record(version(), param, currentText, attempted == null ? null : fmt(attempted),
                source, reason, false, guardNote);
        log.info("[控制面] 提案被驳回 param={} value={} source={} note={}", param, attempted, source, guardNote);
    }

    /** 一键回滚：把最后一次生效的变更改回去。 */
    public Optional<String> rollbackLast() {
        Optional<ConfigAuditRepository.Entry> last = audit.lastAccepted();
        if (last.isEmpty()) {
            return Optional.empty();
        }
        ConfigAuditRepository.Entry e = last.get();
        Optional<ConfigParam> param = ConfigParam.byKey(e.param());
        if (param.isEmpty()) {
            return Optional.empty();
        }
        double back = Double.parseDouble(e.oldValue());
        apply(param.get(), back, "ROLLBACK",
                "回滚审计记录 #" + e.id() + "（原变更来自 " + e.source() + "）", "manual rollback");
        return Optional.of(e.param() + " 回滚到 " + e.oldValue());
    }

    /** 压测前重置，保证每轮实验的起点一致。 */
    public void resetToDefaults() {
        for (Map.Entry<ConfigParam, Double> e : defaults().entrySet()) {
            redis.opsForHash().put(HASH_KEY, e.getKey().key(), fmt(e.getValue()));
            cache.put(e.getKey(), e.getValue());
        }
        redis.opsForValue().increment(VERSION_KEY);
        redis.convertAndSend(CHANNEL, "reset");
        log.info("[控制面] 热配置已重置为初始值 {}", snapshot());
    }

    /** 整数值不带小数点地格式化，让审计表和日志好读一些。 */
    public static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
