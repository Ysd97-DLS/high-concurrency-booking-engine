package com.flashpilot.dataplane.order;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashpilot.clinic.domain.ApptPersistRepository;

/**
 * 成交事件落库的端口。
 *
 * <p>它是引擎侧唯一知道"落库"这件事的地方，具体落成什么由业务域决定——
 * 现在落成挂号预约单，换成限量首发就落成订单，引擎这一层不用改。
 *
 * <p><b>不超卖的最后一道防线在这里</b>：加号数的 UPDATE 带
 * {@code booked_slots + n <= total_slots} 条件，加不上就抛异常让整批回滚。
 * 即使 Redis 侧的分桶、号段、租约全乱了，MySQL 这一句物理上也不允许超发。
 */
@Service
public class OrderPersistService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderPersistService.class);

    /** 整批加起来会超出总号数时抛出。调用方应退回逐条处理，精确找到边界在哪一条。 */
    public static class OversoldBlockedException extends RuntimeException {
        public OversoldBlockedException(long poolId) {
            super("poolId=" + poolId + " 已达总号数，MySQL 拦截了一次超卖");
        }
    }

    public enum Outcome {
        /** 正常入库。 */
        INSERTED,
        /**
         * 没插进去，原因待查。
         *
         * <p>刻意<b>不叫 DUPLICATE</b>：{@code INSERT IGNORE} 返回 0 只表示这行没进去，
         * 它对「真重复」和「其它约束失败」给的是同一个结果。叫 DUPLICATE 会诱使调用方
         * 直接 ACK 掉消息，而号已经从 Redis 扣走 —— P6 实测这样丢了 19389 个号。
         * 真正的原因要在事务外查证，见 {@link #classifyNotInserted}。
         */
        NOT_INSERTED
    }

    /**
     * 落库既没成功也不是重复。抛出去让消费者保留 pending 并重试 ——
     * 静默当成重复才是最坏的处理方式：号已扣、单没落、消息被 ACK。
     */
    public static class PersistFailedException extends RuntimeException {
        public PersistFailedException(long poolId, long holderId, String eventId) {
            super("落库未成功且查不到已有单：poolId=" + poolId
                    + " holderId=" + holderId + " eventId=" + eventId);
        }
    }

    private final ApptPersistRepository appts;

    public OrderPersistService(ApptPersistRepository appts) {
        this.appts = appts;
    }

    /** 单条落库。批量遇到超卖时退回这条路径，精确定位边界。 */
    @Transactional
    public Outcome persist(long holderId, long poolId, String eventId) {
        int n = appts.insertBatch(poolId, List.of(new OrderEvent(holderId, poolId, eventId)));
        if (n == 0) {
            // 没插进去。**不能在这里判是不是重复** —— 这个方法在事务里，
            // REPEATABLE READ 的快照看不到并发提交的行，会把真重复误判成真失败。
            // 交给调用方在事务外查证，见 classifyNotInserted。
            return Outcome.NOT_INSERTED;
        }
        if (!appts.incrementBookedBy(poolId, 1)) {
            throw new OversoldBlockedException(poolId);
        }
        return Outcome.INSERTED;
    }

    /** 批量落库的结果。 */
    /**
     * 批量落库结果。
     *
     * @param inserted    真正插进去的行数
     * @param notInserted 没插进去的事件（可能是真重复，也可能是真失败）——
     *                    <b>原因必须在这个事务之外查证</b>，见 {@link #classifyNotInserted}
     */
    public record BatchResult(int inserted, java.util.List<OrderEvent> notInserted) {

        /** 整批都插进去了，不需要再查证。 */
        public boolean complete() {
            return notInserted.isEmpty();
        }
    }

    /**
     * 查证结果：没插进去的那些，哪些是真重复、哪些要重试。
     *
     * @param duplicate 已经有占号单的条数 —— 可以 ACK
     * @param failed    查不到已有单的事件 id —— <b>绝不能 ACK</b>
     */
    public record Verdict(int duplicate, java.util.List<String> failed) {
    }

    /**
     * 把一批成交事件在<b>同一个事务</b>里落库：多行 INSERT + 一次聚合 UPDATE。
     *
     * <p>这是消费吞吐的关键。逐条落库时每条一个事务，而事务里那句更新排班已生效号数的 SQL
     * 打的是同一行，所有消费者线程都在抢同一把行锁，吞吐被锁持有时间锁死。
     * 实测逐条只有约 1000 条/秒，而数据面能产出 3300+/秒。批量之后 N 条只需要一次行锁。
     *
     * <p>整批必须同一个 {@code poolId}，调用方负责分组。
     */
    @Transactional
    public BatchResult persistBatch(long poolId, List<OrderEvent> events) {
        if (events.isEmpty()) {
            return new BatchResult(0, List.of());
        }
        int inserted = appts.insertBatch(poolId, events);
        if (inserted > 0 && !appts.incrementBookedBy(poolId, inserted)) {
            throw new OversoldBlockedException(poolId);
        }
        if (inserted == events.size()) {
            return new BatchResult(inserted, List.of());
        }
        // 插入行数不足。**这里只报告「哪些没进去」，不判断原因** ——
        // 判断必须在这个事务之外做，理由见 classifyNotInserted 的注释。
        //
        // 至于为什么不能像原来那样用减法 `duplicate = events.size() - inserted`：
        // INSERT IGNORE 对「真重复」和「其它任何约束失败」返回完全相同的结果
        // （该行不插入、不报错、不区分）。P6 Redis 主从切换实测，减法让
        // 19389 个号被计为「重复购买」并 ACK —— 号从桶扣走、MySQL 没单、
        // 不重试不进死信，只有一致性等式③ 报出 19389 的守恒残差。
        List<OrderEvent> notInserted = new java.util.ArrayList<>(events.size() - inserted);
        // INSERT IGNORE 不告诉我们是哪几行被忽略的，只给一个总数，
        // 所以整批都要交给事务外去查证。批大小 256，这点开销只在异常路径上付。
        notInserted.addAll(events);
        return new BatchResult(inserted, notInserted);
    }

    /**
     * 查证没插进去的那些到底是真重复还是真失败。<b>必须在落库事务之外调用。</b>
     *
     * <h2>为什么不能放在事务里</h2>
     *
     * MySQL 默认隔离级别是 REPEATABLE READ：事务里的 {@code SELECT} 读的是
     * 事务开始时建立的快照，<b>看不到之后其它事务提交的行</b>。
     * 而 {@code INSERT} 的唯一索引检查走的是<b>当前读</b>，看得见最新已提交数据。
     *
     * <p>两者对「这行存在吗」给出不同答案，于是在事务内查证会得出荒谬的结论：
     * INSERT 说「已经有了，我忽略」，紧接着的 SELECT 说「没有啊」。
     *
     * <p>这不是推理出来的 —— P6 实测抓到过：一条卡在 pending 的消息 holderId=165838，
     * MySQL 里明明有 {@code A1001-24492 / 165838 / PENDING_PAY / 165838-101-2099-01-01}，
     * 而事务内查证查不到，于是被判成「真失败」并无限重试，日志刷了 2014 条
     * 「凭证号没有冲突，需要人工排查」。后果比原来的丢号轻（号没丢），
     * 但消费会被这些永远判不对的消息拖住。
     *
     * <p>放在事务外，每次查证都是一个新快照，能看到并发 flusher 刚提交的行。
     */
    public Verdict classifyNotInserted(long poolId, int inserted, List<OrderEvent> notInserted) {
        if (notInserted.isEmpty()) {
            return new Verdict(0, List.of());
        }
        List<Long> patientIds = new java.util.ArrayList<>(notInserted.size());
        for (OrderEvent e : notInserted) {
            patientIds.add(e.holderId());
        }
        java.util.Set<Long> holding = appts.findHoldingPatients(poolId, patientIds);
        Verdict verdict = classify(inserted, notInserted, holding);
        if (!verdict.failed().isEmpty()) {
            log.error("有 {} 条既没插入也查不到已有单，交回重试（poolId={} 待查证={} 本批插入={} 重复={}）",
                    verdict.failed().size(), poolId, notInserted.size(), inserted, verdict.duplicate());
        }
        return verdict;
    }

    /**
     * 「没插进去」到底是真重复还是真失败 —— 判定规则本身，不碰数据库。
     *
     * <p>单独抽出来是为了让它能被断言。原先这段判定长在 {@code classifyNotInserted}
     * 里、被 JdbcTemplate 与 {@code @Transactional} 缠着，单测只能在测试类里复刻一份
     * 同形状的逻辑，于是<b>七条断言与产线代码零耦合</b>：产线把
     * {@code Math.max(0, duplicate - inserted)} 那步删掉，测试一条都不会红。
     * 引入 JaCoCo 后这件事直接显形（测试全绿而该包行覆盖为 0），才做了这次抽取。
     *
     * @param inserted    本批真正插进去的行数
     * @param notInserted 待查证的事件（INSERT IGNORE 不告诉是哪几行，所以整批都在这里）
     * @param holding     这批患者里，当前在库中已有占号单的那些
     */
    static Verdict classify(int inserted, List<OrderEvent> notInserted, java.util.Set<Long> holding) {
        List<String> failed = new java.util.ArrayList<>();
        int duplicate = 0;
        for (OrderEvent e : notInserted) {
            if (holding.contains(e.holderId())) {
                duplicate++;
            } else {
                failed.add(e.eventId());
            }
        }
        // holding 里含了本批刚插进去的那些（它们现在也「有占号单」），扣掉才是真正被挡掉的。
        duplicate = Math.max(0, duplicate - inserted);
        return new Verdict(duplicate, failed);
    }
}
