package com.flashpilot.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 活实例注册表，唯一的用途是回答一个问题：<b>现在到底有几个实例在跑？</b>
 *
 * <p>为什么需要它：{@code dedupe.mode=LOCAL} 把「一人一单」的判重放在进程内存里，
 * 前提是网关按 userId 一致性哈希做粘性路由。这个前提写在 application.yml 的注释里，
 * 而<b>没人会在部署第二个实例时回去读那段注释</b>。
 *
 * <p>违反它的后果实测过（双实例、各 100 并发、共用 2000 个 userId、库存 10000）：
 * 两个实例各放同一个 user 过一次 → 成交 4000 次而 user 只有 2000 个 →
 * 落库时撞 {@code uk_user_item} 唯一索引，<b>2000 个号（20% 的号源）人间蒸发</b>：
 * 从 Redis 桶里扣走了，既没落进订单，也没还回桶。
 *
 * <p>最难受的是它<b>完全不可观测</b>：客户端零异常、全部 code=200，
 * 每个患者都收到「抢号成功」。只有一致性等式③ 能看见（{@code vanished=2000}）。
 * 而对账补偿会正确地拒绝自动修复 —— 2000 远超单轮上限 100，
 * 那个量级更可能是校验器算错，补偿反而会放大成超卖。也就是说：
 * <b>这个配置错误一旦发生，只能人工善后。</b>
 *
 * <p>所以这里的做法是让它在<b>发生之前</b>就喊出来：每个实例往注册表登记一行，
 * LOCAL 模式下一旦看到不止一个实例，就打 ERROR 级日志。
 * 运维不读 yml 注释，但会看启动日志里的红字。
 *
 * <h2>为什么用一个 Hash 而不是每个实例一个带 TTL 的键</h2>
 *
 * 一开始写的是 {@code fp:instance:<id>} + TTL，检查时 {@code KEYS fp:instance:*}。
 * 那样有个要命的问题：<b>KEYS 是全库扫描</b>，而这个检查挂在 3 秒一次的心跳上——
 * 压测时 Redis 里有百万级键，等于每 3 秒让 Redis 卡一下，
 * 为了一个诊断功能去拖累热路径，完全不值得。
 *
 * <p>换成单个 Hash：field 是实例 id，value 是上次心跳的毫秒时间戳。
 * 一次 HGETALL 的代价只和实例数量成正比（个位数），和库里有多少键无关。
 * 代价是没有自动过期，所以过期判断改成比时间戳，并在检查时顺手 HDEL 掉死实例。
 *
 * <p>登记动作挂在已有的租约心跳上（见 {@code LeaseMaintainer#heartbeat}），
 * 不新增 {@code @Scheduled} 任务 —— 调度池的容量不变量已经够难维护了。
 */
@Component
public class InstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    /** 注册表：Hash，field = instanceId，value = 上次心跳的 epoch 毫秒。 */
    private static final String KEY = "fp:instances";

    /**
     * 超过这个时长没心跳就算死了。心跳间隔默认 3 秒，取 15 秒即容忍连续丢 4 次 ——
     * 判死太快会让一次 GC 停顿造成「实例数忽然变少」的假告警。
     */
    private static final long STALE_MS = 15_000;

    private final StringRedisTemplate redis;
    private final InstanceIdentity identity;
    private final FlashPilotProperties props;

    /** 上次告警时看到的实例数。只在数量变化时打日志，否则每 3 秒一条红字会把日志刷没。 */
    private final AtomicInteger lastWarned = new AtomicInteger(0);

    public InstanceRegistry(StringRedisTemplate redis, InstanceIdentity identity,
                            FlashPilotProperties props) {
        this.redis = redis;
        this.identity = identity;
        this.props = props;
    }

    /**
     * 登记自己并检查同伴数量。由租约心跳调用，失败只记日志不抛 ——
     * 注册表是诊断设施，它挂掉不该影响下单。
     */
    public void registerAndCheck() {
        try {
            redis.opsForHash().put(KEY, identity.id(), String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("实例登记失败：{}", e.toString());
            return;
        }
        checkDedupeSafety();
    }

    /**
     * 当前活着的实例 id，按字典序排好，便于日志和看板稳定显示。
     * 顺手清掉超过 {@link #STALE_MS} 没心跳的死实例，避免注册表无限增长
     * （每次重启换一个随机 instanceId 的话，攒起来会很快）。
     */
    public List<String> aliveInstances() {
        try {
            Map<Object, Object> all = redis.opsForHash().entries(KEY);
            if (all == null || all.isEmpty()) {
                return List.of(identity.id());
            }
            Split split = partition(all, System.currentTimeMillis(), STALE_MS);
            if (!split.dead().isEmpty()) {
                try {
                    redis.opsForHash().delete(KEY, split.dead().toArray());
                } catch (Exception ignored) {
                    // 清理失败无所谓，下一轮还会再试
                }
            }
            // 一个都不新鲜时退回「至少有我自己」：这时候更可能是本机时钟或注册表出了问题，
            // 而返回空列表会让调用方以为「没有实例在跑」，比返回 1 更容易引出错误结论。
            return split.alive().isEmpty() ? List.of(identity.id()) : split.alive();
        } catch (Exception e) {
            return List.of(identity.id());
        }
    }

    /** {@link #partition} 的结果：活的 id（已排序）和该清理的 field。 */
    public record Split(List<String> alive, List<Object> dead) {
    }

    /**
     * 按心跳时间戳把注册表分成「活的」和「该清理的」。
     *
     * <p>抽成静态纯函数只为一件事：这段判断有真实的边界（时间戳恰好等于阈值、
     * 值不是数字、时钟回拨导致时间戳在未来），而它们全都不需要 Redis 就能测。
     * 项目里 GuardDecider、ReconcileDecider 都是这么切的。
     *
     * <p>注意时钟回拨的处理：{@code now - ts} 为负时算「新鲜」而不是「过期」。
     * 未来时间戳意味着写它的那个实例的时钟比本机快，把它判死会导致
     * 两个实例互相把对方从注册表里删掉，然后各自以为自己是唯一实例 ——
     * 那正好绕过这里要防的告警。
     */
    static Split partition(Map<Object, Object> entries, long now, long staleMs) {
        List<String> alive = new ArrayList<>(entries.size());
        List<Object> dead = new ArrayList<>();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            long ts;
            try {
                ts = Long.parseLong(String.valueOf(e.getValue()).trim());
            } catch (NumberFormatException ignored) {
                dead.add(e.getKey());
                continue;
            }
            if (now - ts > staleMs) {
                dead.add(e.getKey());
            } else {
                alive.add(String.valueOf(e.getKey()));
            }
        }
        Collections.sort(alive);
        return new Split(alive, dead);
    }

    /**
     * LOCAL 判重 + 多实例 = 静默少卖。这里只负责喊，不自动切模式 ——
     * 悄悄改判重模式会让热路径多一次 Redis RTT，那是个该由人拍板的性能决定。
     */
    private void checkDedupeSafety() {
        if (props.dedupe().mode() != FlashPilotProperties.Dedupe.Mode.LOCAL) {
            lastWarned.set(0);
            return;
        }
        List<String> alive = aliveInstances();
        if (alive.size() <= 1) {
            lastWarned.set(0);
            return;
        }
        if (lastWarned.getAndSet(alive.size()) == alive.size()) {
            return;   // 数量没变，不重复刷屏
        }
        log.error("""
                ════════ 判重模式与部署形态不匹配 ════════
                dedupe.mode=LOCAL 但检测到 {} 个实例在跑：{}
                LOCAL 把「一人一单」判在进程内存里，只有在网关按 userId 做粘性路由时才成立。
                没有粘性路由的话，同一个患者会在每个实例上各抢到一个号，落库时撞唯一索引，
                号从 Redis 扣走却既不落单也不归还 —— 实测双实例下 20% 的号源就这么没了，
                而客户端全部收到「成功」，只有一致性等式能看见。
                要么给网关配 userId 一致性哈希，要么把 dedupe.mode 改成 REDIS。
                ══════════════════════════════════════""",
                alive.size(), alive);
    }

    /**
     * 启动就绪时立刻登记并检查一次，不等第一个心跳（3 秒）。
     *
     * <p>启动日志是运维唯一会认真看的一段输出，这条警告必须落在那里面。
     * 等心跳的话它会混在压测流量的日志里，等于没说。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        registerAndCheck();
        List<String> alive = aliveInstances();
        log.info("活实例 {} 个：{}（判重模式 {}）", alive.size(), alive, props.dedupe().mode());
    }

    /** 当前判重模式的名字，给看板显示用。 */
    public String dedupeMode() {
        return props.dedupe().mode().name();
    }

    /** 给看板用：LOCAL 模式下有多实例就是不安全的部署。 */
    public boolean dedupeUnsafe() {
        return props.dedupe().mode() == FlashPilotProperties.Dedupe.Mode.LOCAL
                && aliveInstances().size() > 1;
    }
}
