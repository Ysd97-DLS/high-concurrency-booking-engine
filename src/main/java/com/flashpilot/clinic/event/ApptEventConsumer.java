package com.flashpilot.clinic.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预约状态变更的审计流水 —— <b>顺序消费</b>。
 *
 * <p>{@code consumeMode = ORDERLY} 是这个消费者的关键：它让同一个队列内的消息
 * <b>串行</b>处理。配上生产端按凭证号选队列，效果就是
 * 「同一张单的状态变更严格按发生顺序到达」。
 *
 * <p>换成默认的 {@code CONCURRENTLY} 会让顺序保证<b>完全失效</b>：
 * 生产端把消息按序放进了同一个队列，消费端却开多个线程并发取 —— 前功尽弃。
 * 这是顺序消息最容易踩的坑，因为<b>生产端那半边看起来是对的</b>，
 * 而且低并发时大概率不出错，压上量才暴露。
 *
 * <h2>为什么消费端刻意做得很轻</h2>
 *
 * 顺序消费是串行的：一条消息处理慢，它后面同队列的消息全都在等。
 * 所以这里只在内存里留一条环形的审计流水（给运营看板用），
 * 不落库、不调外部接口 —— 重活会把队列堵住，而堵住的表现是
 * 「状态变更时间线停止更新」，很难一眼归因到这里。
 */
@Component
@RocketMQMessageListener(
        topic = ApptEventPublisher.TOPIC,
        consumerGroup = "fp-appt-event-audit",
        consumeMode = ConsumeMode.ORDERLY,
        // ORDERLY 的默认重试是**无限次 + 挂起当前队列**。onMessage 本身不会抛，
        // 但反序列化在框架层：一条解析不动的消息（滚动升级时结构变更、脏数据）
        // 会让它所在的队列永久停止消费 —— 约 1/4 的凭证号时间线从此不再更新，
        // 而日志里只有周期性的重试记录。设上限后超限的消息会被跳过进死信，
        // 「丢一条审计流水」和「堵死一个队列」之间选前者。
        maxReconsumeTimes = 16)
public class ApptEventConsumer implements RocketMQListener<ApptEventPublisher.ApptStateChanged> {

    private static final Logger log = LoggerFactory.getLogger(ApptEventConsumer.class);

    /** 保留多少条流水。内存里的看板数据，不是账本 —— 账本是 t_appointment。 */
    private static final int KEEP = 200;

    private final Deque<Map<String, Object>> timeline = new ArrayDeque<>();
    private final AtomicLong consumed = new AtomicLong();

    /**
     * 每张单最后看到的事件（终态 + 本条事件的签名）。用来检测<b>乱序</b>并识别<b>重投</b>。
     *
     * <p>这个检测不是多余的：顺序保证依赖「生产端选队列 + 消费端 ORDERLY」两半都对，
     * 而其中任何一半被改掉都不会报错，只会静默乱序。
     * 有了它，乱序会变成一条 error 日志，而不是几个月后对不上的统计数字。
     *
     * <p><b>有界 LRU 而不是裸 ConcurrentHashMap</b>：每个凭证号一条、永不清理的话，
     * 一轮 6 万单的压测就留 6 万条，长期运行就是内存泄漏。被逐出的旧单
     * 只是失去乱序检测（当成第一次见到），流水本身不受影响。
     */
    private static final int MAX_TRACKED = 100_000;
    private final Map<String, String[]> lastState = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > MAX_TRACKED;
                }
            });
    private final AtomicLong outOfOrder = new AtomicLong();
    private final AtomicLong redelivered = new AtomicLong();

    @Override
    public void onMessage(ApptEventPublisher.ApptStateChanged e) {
        if (e == null || e.apptNo() == null) {
            return;
        }
        String sig = e.from() + ">" + e.to() + "@" + e.atMs();
        String[] prev = lastState.put(e.apptNo(), new String[] {e.to(), sig});
        if (prev != null && sig.equals(prev[1])) {
            // 同一条事件到达两次 —— at-least-once 的正常重投（rebalance、orderly 重试、
            // offset 回退都会造成），不是乱序，也不该在流水里出现两遍。
            // 不识别它的话，重投的 PENDING_PAY→BOOKED 会撞上 prev=BOOKED 被记成乱序，
            // 而日志还会笃定地把人引向生产端配置 —— 假警报比漏报更毁检测器的信誉。
            lastState.put(e.apptNo(), prev);
            redelivered.incrementAndGet();
            return;
        }
        consumed.incrementAndGet();

        if (prev != null && e.from() != null && !prev[0].equals(e.from())) {
            // 上一条事件的终点，应该等于这一条事件的起点。对不上就是乱序。
            long n = outOfOrder.incrementAndGet();
            log.error("预约状态事件乱序：apptNo={} 上次结束于 {}，本次却从 {} 开始（累计 {} 次）。"
                    + "可能原因按概率排：① 发布竞态 —— 改库和 publish 不是原子的，前一次迁移"
                    + "提交后线程停顿，后一次迁移抢先发布（根治要在事件里带 DB 侧版本号）；"
                    + "② 生产端不再用 syncSendOrderly + 凭证号作 sharding key；"
                    + "③ 消费端 consumeMode 被改回 CONCURRENTLY",
                    e.apptNo(), prev[0], e.from(), n);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("apptNo", e.apptNo());
        row.put("from", e.from());
        row.put("to", e.to());
        row.put("reason", e.reason());
        row.put("atMs", e.atMs());
        synchronized (timeline) {
            timeline.addFirst(row);
            while (timeline.size() > KEEP) {
                timeline.removeLast();
            }
        }
    }

    /** 最近的状态变更流水，运营看板用。 */
    public List<Map<String, Object>> recent(int limit) {
        synchronized (timeline) {
            List<Map<String, Object>> out = new ArrayList<>(Math.min(limit, timeline.size()));
            for (Map<String, Object> row : timeline) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(row);
            }
            return out;
        }
    }

    public long consumedCount() {
        return consumed.get();
    }

    /**
     * 乱序次数。已剔除重投（同签名事件不计入），所以稳定非零时是真乱序 ——
     * 但注意它包含「发布竞态」这一已知的非配置成因，见 onMessage 里的日志文案。
     */
    public long outOfOrderCount() {
        return outOfOrder.get();
    }

    /** 被识别为重投而跳过的条数。at-least-once 下非零是正常的。 */
    public long redeliveredCount() {
        return redelivered.get();
    }
}
