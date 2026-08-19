package com.flashpilot.dataplane.stream;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.config.LuaScripts;
import com.flashpilot.config.Replies;
import com.flashpilot.dataplane.order.OrderPersistService;

/**
 * 成交事件的消费者：把 Redis Stream 里的事件落成 MySQL 订单。
 *
 * <p>削峰的意义在这里体现得最直白：秒杀的写入是极短时间的尖峰，但<b>总量很小</b>
 * （被库存上限约束）。Stream 把「瞬时几万 QPS」摊成「消费者能力内的稳定流量」，
 * MySQL 就不再是瓶颈。
 *
 * <p>三种异常都处理了：
 * <ul>
 *   <li><b>重复</b>：唯一索引 + 幂等判定，重复消息直接 ACK。</li>
 *   <li><b>丢失</b>：手动 ACK。处理失败就不 ACK，消息留在 pending 列表里；
 *       {@link #claimIdlePending()} 会把长时间没人管的消息抢回来重试。</li>
 *   <li><b>毒消息</b>：重试超过上限就 ACK 掉并记为死信，避免一条坏消息把消费者卡死。</li>
 * </ul>
 */
@Component
public class OrderStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderStreamConsumer.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final LuaScripts scripts;
    private final FlashPilotProperties props;
    private final InstanceIdentity identity;
    private final OrderPersistService persistService;
    private final ConsumerStats stats;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    /** 订阅句柄，按序号存。看门狗靠 {@code isActive()} 判断存活。 */
    private final Map<Integer, org.springframework.data.redis.stream.Subscription> subscriptions =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ---------- 批量落库 ----------
    //
    // Spring 的 StreamListener 是「一条一条」回调的（batchSize 只影响 XREADGROUP COUNT），
    // 所以直接在回调里落库必然是每条一个事务。而事务里那句更新 t_item 打的是同一行，
    // 十万订单抢同一把行锁 —— 实测吞吐只有约 1000 条/秒，而数据面能产出 3300+/秒。
    //
    // 这里把「收消息」和「落库」解耦：回调只负责入队，由独立的 flusher 线程攒批，
    // 一个事务里多行 INSERT + 一次聚合 UPDATE，落库成功之后再整批 ACK。
    //
    // 顺序不能反：先入库、后 ACK。反了一旦入库失败消息就从 pending 消失，那是真丢单。
    // 进程被 kill 时队列里的消息没 ACK，会留在 pending 里由 claimIdlePending 抢回来，语义是安全的。
    private final java.util.concurrent.BlockingQueue<OrderEnvelope> inbox =
            new java.util.concurrent.LinkedBlockingQueue<>(200_000);
    private java.util.concurrent.ExecutorService flushers;
    private volatile boolean running = true;

    /** 队列里的一条消息：业务字段 + 用于 ACK 的 stream id。 */
    private record OrderEnvelope(String streamId, long holderId, long poolId) {
    }

    public OrderStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                               LuaScripts scripts, FlashPilotProperties props, InstanceIdentity identity,
                               OrderPersistService persistService, ConsumerStats stats) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.scripts = scripts;
        this.props = props;
        this.identity = identity;
        this.persistService = persistService;
        this.stats = stats;
    }

    @PostConstruct
    public void start() {
        String streamKey = props.stream().key();
        String group = props.stream().group();
        try {
            redis.execute(scripts.ensureGroup, List.of(streamKey), group);
        } catch (Exception e) {
            log.warn("创建消费组失败（如果是 BUSYGROUP 可以忽略）：{}", e.toString());
        }

        // 显式给一个「只记日志、不取消订阅」的错误处理器。
        // 默认处理器在某些错误下会终止订阅，那样消费者会悄无声息地停掉 ——
        // 你以为它在跑，实际上消息一直堆着，这种问题极难查。
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>>
                options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(props.stream().pollTimeoutMs()))
                .batchSize(props.stream().batchSize())
                .errorHandler(this::onPollError)
                .build();

        pruneStaleConsumers(streamKey, group);

        container = StreamMessageListenerContainer.create(connectionFactory, options);
        for (int i = 0; i < props.stream().consumerCount(); i++) {
            subscribeOne(i);
        }
        container.start();

        int flusherCount = props.stream().flusherCount();
        flushers = java.util.concurrent.Executors.newFixedThreadPool(flusherCount, r -> {
            Thread t = new Thread(r, "order-flusher");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < flusherCount; i++) {
            flushers.submit(this::flushLoop);
        }

        log.info("Stream 消费者启动 stream={} group={} 消费者数={} flusher={} 批大小={}",
                streamKey, group, props.stream().consumerCount(), flusherCount, props.stream().flushBatchSize());
    }

    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
        }
        // 先停收消息，再把队列里剩下的落完，最后才退出。
        // 队列里没落库的消息本身没 ACK，即使这里没排空也会留在 pending 由 claim 抢回来，不会丢。
        running = false;
        if (flushers != null) {
            flushers.shutdown();
            try {
                flushers.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 启动时清理消费组里的僵尸消费者。
     *
     * <p>消费者名字是 {@code instanceId + "-c" + i}，而 instanceId 每次启动都不同（含 PID）。
     * Redis 的消费组会<b>永久保留</b>每个用过的消费者名，所以每重启一次就多留 5 个死消费者。
     * 实测 7 次重启后 {@code XINFO GROUPS} 里的 consumers 变成了 34 个。
     *
     * <p>后果不只是脏：{@code XINFO CONSUMERS} 和 {@code XAUTOCLAIM} 都要遍历这个列表，
     * 数量涨上去之后是实打实的开销，而且排查问题时根本看不出哪个才是活的。
     *
     * <p>删除条件必须<b>三个</b>同时满足：
     * <ol>
     *   <li>不是本实例的；</li>
     *   <li>{@code pending == 0} —— 不为 0 说明它手里还攥着没 ACK 的消息，
     *       删掉这些消息就再也没人认领了，那是真丢单；</li>
     *   <li>{@code idle} 超过阈值 —— <b>这一条最容易漏</b>。
     *       只判断「不是本实例的」会把<i>其它正在运行的实例</i>的消费者也删掉：
     *       多实例部署时每次有实例重启，就会顺手删掉同伴的消费者。
     *       活着的实例 idle 只有几百毫秒，用 idle 阈值就能干净地区分「同伴」和「遗骸」。</li>
     * </ol>
     */
    private static final long STALE_CONSUMER_IDLE_MS = 5 * 60 * 1000L;

    /** 连续多少次轮询错误就升级成致命告警。按 pollTimeout=2s 算，10 次约等于卡了 20 秒。 */
    private static final int FATAL_ERROR_STREAK = 10;

    private final java.util.concurrent.atomic.AtomicInteger pollErrorStreak =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 最近一次轮询错误是否为「连到了只读副本」。健康检查和实验脚本靠它判断消费端是否已死。 */
    private volatile String consumerFatalReason = null;

    /**
     * Stream 轮询出错的统一处理。
     *
     * <p><b>这里修的是 P6 主从切换实验暴露的一个严重问题。</b>
     * 原来只写了一行 {@code log.warn("已忽略，继续消费")}，用意是「不要让一次抖动取消订阅」。
     * 但主从切换之后本实例连的旧主库降级成了只读副本，而 {@code XREADGROUP} 是写命令
     * （它要推进消费组的 last-delivered-id），于是<b>每一次轮询都失败</b>。
     * 结果是：消费者永久停止消费、69380 条消息卡在 pending、应用的健康检查却一切正常，
     * 日志里只有海量看起来无害的 WARN。<b>静默降级比崩溃危险得多。</b>
     *
     * <p>所以改成：区分「偶发抖动」和「结构性故障」。连续失败到阈值就打 ERROR 并记下原因，
     * 让它在日志里刺眼、并且能被外部探测到 —— 真正的修复（客户端跟随主从切换）需要
     * Sentinel 感知的连接工厂，那是生产部署的事，但至少不能让它静默地死。
     */
    private void onPollError(Throwable t) {
        // 必须顺着 cause 链找，不能只看顶层 message。
        // Spring Data Redis 把底层错误包了一层，顶层 getMessage() 只是 "Error in execution"，
        // 真正的 "READONLY You can't write against a read only replica" 在 cause 里。
        // 第一版只查顶层，于是把结构性故障误报成了普通的连续失败。
        String msg = describeCauses(t);
        boolean readOnly = msg.contains("READONLY");
        int streak = pollErrorStreak.incrementAndGet();

        if (streak == FATAL_ERROR_STREAK || (readOnly && streak == 1)) {
            consumerFatalReason = readOnly
                    ? "连到了只读副本（READONLY）—— 极可能刚发生过 Redis 主从切换，"
                      + "本实例仍连着旧主库。XREADGROUP 是写命令，在副本上必然失败，消费已完全停止。"
                      + "需要用 Sentinel 感知的客户端跟随切换，或手工重启实例指向新主库。"
                    : "连续 " + streak + " 次轮询失败：" + msg;
            log.error("[消费端致命] {}", consumerFatalReason);
        } else if (streak % 200 == 0) {
            log.warn("Stream 轮询持续出错（第 {} 次）：{}", streak, msg);
        }
    }

    /** 把整条 cause 链拼成一行，用于关键字识别和日志。 */
    private static String describeCauses(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 6) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /** 轮询成功一次就清零，避免偶发抖动累积成误报。 */
    private void onPollSuccess() {
        if (pollErrorStreak.get() != 0) {
            pollErrorStreak.set(0);
            consumerFatalReason = null;
        }
    }

    /** 供健康检查 / 实验脚本读取：非 null 说明消费端处于结构性故障，不是简单落后。 */
    public String consumerFatalReason() {
        return consumerFatalReason;
    }

    private void pruneStaleConsumers(String streamKey, String group) {
        try {
            org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumers consumers =
                    redis.opsForStream().consumers(streamKey, group);
            int removed = 0;
            int keptWithPending = 0;
            int keptAlive = 0;
            for (org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer c : consumers) {
                if (c.consumerName().startsWith(identity.id())) {
                    continue;                       // 本实例的，留着
                }
                if (c.pendingCount() > 0) {
                    keptWithPending++;              // 手里有未 ACK 的消息，绝对不能删
                    continue;
                }
                if (c.idleTimeMs() < STALE_CONSUMER_IDLE_MS) {
                    keptAlive++;                    // 还活跃，是别的在跑的实例，不是遗骸
                    continue;
                }
                redis.opsForStream().deleteConsumer(streamKey,
                        Consumer.from(group, c.consumerName()));
                removed++;
            }
            if (removed > 0 || keptWithPending > 0 || keptAlive > 0) {
                log.info("清理僵尸消费者：删除 {} 个，保留 {} 个（有未 ACK 消息）、{} 个（仍活跃，属于其它实例）",
                        removed, keptWithPending, keptAlive);
            }
        } catch (Exception e) {
            log.warn("清理僵尸消费者失败（不影响启动）：{}", e.toString());
        }
    }

    /** 建立第 i 个订阅并记下句柄，供看门狗检查存活。 */
    private void subscribeOne(int i) {
        String consumerName = identity.id() + "-c" + i;
        org.springframework.data.redis.stream.Subscription sub = container.receive(
                Consumer.from(props.stream().group(), consumerName),
                StreamOffset.create(props.stream().key(), ReadOffset.lastConsumed()),
                this::onMessage);
        subscriptions.put(i, sub);
    }

    /**
     * 看门狗：检测订阅是否还活着，死了就重建。
     *
     * <p><b>为什么必须有这个东西</b>：{@code StreamMessageListenerContainer} 在遇到
     * Redis 连接失败时会<b>取消订阅并终止轮询线程</b>，而且不会自己恢复。
     * 配 {@code errorHandler} 也救不了 —— errorHandler 只负责「记录」这个错误，
     * 取消订阅的决定是在它之外做的。
     *
     * <p>实测就踩到了：600 并发的脉冲压测中 4 个订阅同时报
     * {@code RedisConnectionFailureException} 全部终止，日志里只留下四行 WARN，
     * 之后消费者永久静默 —— 消息一直堆积，而应用看起来完全健康（端口在听、健康检查 UP）。
     * 这种「静默停工」是最难发现的故障：不报错、不重启、只是不干活了。
     */
    @Scheduled(fixedDelay = 5000)
    public void watchdog() {
        if (container == null) {
            return;
        }
        try {
            if (!container.isRunning()) {
                log.error("[看门狗] 监听容器整体停止，重启");
                container.start();
            }
            for (int i = 0; i < props.stream().consumerCount(); i++) {
                org.springframework.data.redis.stream.Subscription sub = subscriptions.get(i);
                if (sub == null || !sub.isActive()) {
                    // 必须先 cancel 再重建。
                    //
                    // 只 receive() 不 cancel() 会让容器里堆积失效的订阅对象，
                    // 而每个订阅持有一条用于阻塞读的连接 —— 连接不释放，压力越来越大，
                    // 于是订阅失效得更频繁，看门狗重建得更多，形成自我强化的恶性循环。
                    // 实测日志里就是「失效-重建」交替刷屏。
                    if (sub != null) {
                        try {
                            sub.cancel();
                        } catch (Exception ignore) {
                            // 已经死了的订阅 cancel 可能抛，忽略即可
                        }
                    }
                    subscriptions.remove(i);
                    log.error("[看门狗] 第 {} 个订阅已失效，取消并重建", i);
                    subscribeOne(i);
                    stats.subscriptionRestarted();
                }
            }
        } catch (Exception e) {
            log.warn("[看门狗] 检查失败，下个周期重试：{}", e.toString());
        }
    }

    private void onMessage(MapRecord<String, String, String> record) {
        onPollSuccess();      // 收到消息就说明轮询链路是通的，清掉错误连击计数
        String id = record.getId().getValue();
        Map<String, String> body = record.getValue();
        try {
            long holderId = Long.parseLong(body.get("holderId"));
            long poolId = Long.parseLong(body.get("poolId"));
            // 只入队，不落库。落库交给 flusher 攒批做 —— 见字段区那段说明。
            if (!inbox.offer(new OrderEnvelope(id, holderId, poolId))) {
                // 队列满说明落库远远跟不上收消息。这里刻意<b>不</b>丢弃、也不 ACK：
                // 直接退回逐条处理，慢是慢但不会丢单，同时这个日志本身就是「消费能力不足」的信号。
                log.warn("落库队列已满（{}），退回逐条处理", inbox.size());
                handle(id, holderId, poolId);
            }
        } catch (NumberFormatException e) {
            // 消息本身坏了，重试多少次都不会好，直接死信
            log.error("成交事件字段非法，记为死信 id={} body={}", id, body);
            stats.deadLetter();
            ack(id);
        }
    }

    // ---------- 批量落库 ----------

    private void flushLoop() {
        int maxBatch = props.stream().flushBatchSize();
        java.util.List<OrderEnvelope> batch = new java.util.ArrayList<>(maxBatch);
        while (running || !inbox.isEmpty()) {
            try {
                OrderEnvelope first = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                inbox.drainTo(batch, maxBatch - 1);
                flushBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("flusher 出现未预期异常（已继续）：{}", e.toString());
            }
        }
    }

    private void flushBatch(java.util.List<OrderEnvelope> batch) {
        // 按 poolId 分组：一个事务只能对一个商品做聚合 UPDATE。
        // 单商品压测时这里就是一组，多商品时也正确。
        Map<Long, java.util.List<OrderEnvelope>> byItem = new java.util.HashMap<>();
        for (OrderEnvelope e : batch) {
            byItem.computeIfAbsent(e.poolId(), k -> new java.util.ArrayList<>()).add(e);
        }

        for (Map.Entry<Long, java.util.List<OrderEnvelope>> entry : byItem.entrySet()) {
            long poolId = entry.getKey();
            java.util.List<OrderEnvelope> group = entry.getValue();
            java.util.List<com.flashpilot.dataplane.order.OrderEvent> events = new java.util.ArrayList<>(group.size());
            for (OrderEnvelope e : group) {
                events.add(new com.flashpilot.dataplane.order.OrderEvent(e.holderId(), poolId, e.streamId()));
            }
            try {
                OrderPersistService.BatchResult r = persistService.persistBatch(poolId, events);
                for (int i = 0; i < r.inserted(); i++) {
                    stats.consumed();
                }
                if (r.complete()) {
                    ackAll(group);
                } else {
                    // 有行没插进去。查证放在 persistBatch 的事务**之外**：
                    // REPEATABLE READ 的快照看不到并发 flusher 刚提交的行，
                    // 在事务内查会把真重复误判成真失败，然后无限重试（P6 实测刷了 2014 条日志）。
                    OrderPersistService.Verdict v =
                            persistService.classifyNotInserted(poolId, r.inserted(), r.notInserted());
                    for (int i = 0; i < v.duplicate(); i++) {
                        stats.duplicate();
                    }
                    // 只 ACK 真正处理掉的，失败的留在 pending 让 claimIdlePending 抢回重试。
                    //
                    // 以前这里无条件 ackAll —— 而当时「插不进去」一律算成重复，
                    // 于是真失败的消息也被 ACK：号已从 Redis 扣走、MySQL 没有单、
                    // 消息从 pending 消失，任何重试机制都救不回来。P6 实测这样丢了 19389 个号。
                    java.util.Set<String> failed = new java.util.HashSet<>(v.failed());
                    if (failed.isEmpty()) {
                        ackAll(group);
                    } else {
                        java.util.List<OrderEnvelope> done = new java.util.ArrayList<>(group.size());
                        for (OrderEnvelope e : group) {
                            if (!failed.contains(e.streamId())) {
                                done.add(e);
                            }
                        }
                        if (!done.isEmpty()) {
                            ackAll(done);
                        }
                        log.warn("本批 {} 条里有 {} 条落库未成功，保留在 pending 等抢回重试",
                                group.size(), failed.size());
                    }
                }
            } catch (Exception ex) {
                // 整批失败最常见的原因是「批量加起来会超出总库存」（活动尾声）。
                // 退回逐条处理，让 handle() 去精确找出边界落在哪一条 —— 批量是优化，逐条是兜底。
                log.warn("批量落库失败，退回逐条处理 {} 条：{}", group.size(), ex.toString());
                for (OrderEnvelope e : group) {
                    handle(e.streamId(), e.holderId(), poolId);
                }
            }
        }
    }

    private void ackAll(java.util.List<OrderEnvelope> group) {
        String[] ids = new String[group.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = group.get(i).streamId();
        }
        try {
            redis.opsForStream().acknowledge(props.stream().key(), props.stream().group(), ids);
        } catch (Exception e) {
            log.warn("批量 ACK 失败（{} 条），退回逐条 ACK：{}", ids.length, e.toString());
            for (String id : ids) {
                ack(id);
            }
        }
    }

    /** 返回是否已 ACK。claimIdlePending 复用这段逻辑。 */
    private void handle(String id, long holderId, long poolId) {
        try {
            OrderPersistService.Outcome outcome = persistService.persist(holderId, poolId, id);
            if (outcome == OrderPersistService.Outcome.NOT_INSERTED) {
                // 没插进去，原因要在事务外查证 —— 事务内的快照看不到并发提交的行。
                OrderPersistService.Verdict v = persistService.classifyNotInserted(
                        poolId, 0,
                        java.util.List.of(new com.flashpilot.dataplane.order.OrderEvent(holderId, poolId, id)));
                if (!v.failed().isEmpty()) {
                    // 查得清清楚楚：既没插入，也没有已存在的占号单。号已扣走，必须重试。
                    throw new OrderPersistService.PersistFailedException(poolId, holderId, id);
                }
                stats.duplicate();
            } else {
                stats.consumed();
            }
            ack(id);
            clearRetry(id);
        } catch (OrderPersistService.OversoldBlockedException e) {
            // MySQL 的 sold_stock < total_stock 挡下来了。这是最后一道物理防线生效的证据，
            // 重试也没意义（库存确实满了），所以 ACK 掉并计数告警。
            log.error("[超卖拦截] {}", e.getMessage());
            stats.oversoldBlocked();
            ack(id);
            clearRetry(id);
        } catch (Exception e) {
            long attempts = bumpRetry(id);
            if (attempts >= props.stream().maxDeliveries()) {
                log.error("成交事件重试 {} 次仍失败，记为死信 id={} holderId={} poolId={}：{}",
                        attempts, id, holderId, poolId, e.toString());
                stats.deadLetter();
                ack(id);
                clearRetry(id);
            } else {
                // 不 ACK：消息留在 pending 列表里，等下一轮被抢回来重试
                log.warn("成交事件处理失败（第 {} 次），保留在 pending 等重试 id={}：{}", attempts, id, e.toString());
            }
        }
    }

    /**
     * 抢回长时间没 ACK 的消息。
     *
     * <p>没有这一步，消费者实例宕机时它已读取但没处理完的消息会永远躺在 pending 列表里，
     * 用户就会「抢到了但查不到订单」。
     */
    /** 一次 XAUTOCLAIM 抢多少条。原来硬编码成 100，见下面注释里的教训。 */
    private static final int CLAIM_BATCH = 2000;
    /** 一次调度里最多抢几批，避免 PEL 极大时单次调度跑太久。 */
    private static final int CLAIM_MAX_ROUNDS = 20;

    /**
     * 抢回长时间没被 ACK 的消息重新处理。
     *
     * <p><b>批量大小原来硬编码成 100，这是个隐蔽但后果严重的问题。</b>
     * P6 主从切换实验暴露了它：切换瞬间约 2.9 万条消息「已入库但 ACK 丢了」
     * （批量落库是先入库后 ACK，切换掐断了两者之间的连接），PEL 里堆了 28828 条。
     * 而调度间隔是 60 秒、一次只抢 100 条 —— 实测 PEL 以每分钟 100 条的速度下降，
     * <b>清完要 4.75 小时</b>，期间实验脚本一直报「消费者卡住了」。
     *
     * <p>实际上活早就干完了（MySQL 订单数全程不变），PEL 只是记账欠债。
     * 但从外部看，「积压不降」和「真的卡死」长得一模一样，这本身就是个可观测性问题。
     *
     * <p>所以两处一起改：批量 100 → 2000，并且在一次调度里循环抢到空为止。
     * 这样一次大的 ACK 丢失能在一两个周期内自愈，而不是拖几个小时。
     */
    @Scheduled(fixedDelayString = "${flashpilot.stream.claim-idle-ms:15000}")
    public void claimIdlePending() {
        int total = 0;
        try {
            // 游标逐轮推进。**不能每轮都从 '0-0' 重开**：抢到的消息 idle 会被重置，
            // 下一轮虽然不再够格却仍要被扫过，PEL 大时后面几轮全是空扫；
            // 而 Redis 对单次扫描量有内部上限，被截断时返回不足一批，
            // 旧代码会据此判定"已排空"而提前 break —— PEL 始终排不干净。
            String cursor = "0-0";
            for (int round = 0; round < CLAIM_MAX_ROUNDS; round++) {
                List<?> flat = redis.execute(scripts.claimPending,
                        List.of(props.stream().key()),
                        props.stream().group(),
                        identity.id() + "-claimer",
                        String.valueOf(props.stream().claimIdleMs()),
                        String.valueOf(CLAIM_BATCH),
                        cursor);
                if (flat == null || flat.isEmpty()) {
                    break;
                }
                // 第一项是下一轮的游标，其后每三项一条消息
                String next = Replies.asString(flat, 0);
                int claimed = 0;
                for (int i = 1; i + 2 < flat.size(); i += 3) {
                    String id = Replies.asString(flat, i);
                    long holderId = Replies.asLong(flat, i + 1);
                    long poolId = Replies.asLong(flat, i + 2);
                    if (id == null || poolId == 0) {
                        continue;
                    }
                    handle(id, holderId, poolId);
                    claimed++;
                }
                total += claimed;
                // 游标回到 '0-0' 表示 PEL 已完整走了一遍，这才是真正的排空判据 ——
                // 「这一批不足 COUNT」只说明本次扫描被截断，不代表没有更多消息。
                if (next == null || "0-0".equals(next)) {
                    break;
                }
                cursor = next;
            }
            if (total > 0) {
                stats.claimed(total);
                log.warn("抢回 {} 条超时未 ACK 的成交事件并重新处理", total);
            }
        } catch (Exception e) {
            log.warn("pending 抢占失败（已抢回 {} 条）：{}", total, e.toString());
        }
    }

    private void ack(String id) {
        try {
            redis.opsForStream().acknowledge(props.stream().key(), props.stream().group(), id);
        } catch (Exception e) {
            log.warn("ACK 失败 id={}：{}", id, e.toString());
        }
    }

    private long bumpRetry(String id) {
        String key = "fp:retry:" + id;
        Long n = redis.opsForValue().increment(key);
        redis.expire(key, 10, TimeUnit.MINUTES);
        return n == null ? 1L : n;
    }

    private void clearRetry(String id) {
        redis.delete("fp:retry:" + id);
    }
}
