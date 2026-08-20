package com.flashpilot.clinic.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        consumeMode = ConsumeMode.ORDERLY)
public class ApptEventConsumer implements RocketMQListener<ApptEventPublisher.ApptStateChanged> {

    private static final Logger log = LoggerFactory.getLogger(ApptEventConsumer.class);

    /** 保留多少条流水。内存里的看板数据，不是账本 —— 账本是 t_appointment。 */
    private static final int KEEP = 200;

    private final Deque<Map<String, Object>> timeline = new ArrayDeque<>();
    private final AtomicLong consumed = new AtomicLong();

    /**
     * 每张单最后看到的状态。用来检测<b>乱序</b>。
     *
     * <p>这个检测不是多余的：顺序保证依赖「生产端选队列 + 消费端 ORDERLY」两半都对，
     * 而其中任何一半被改掉都不会报错，只会静默乱序。
     * 有了它，乱序会变成一条 error 日志，而不是几个月后对不上的统计数字。
     */
    private final Map<String, String> lastState = new ConcurrentHashMap<>();
    private final AtomicLong outOfOrder = new AtomicLong();

    @Override
    public void onMessage(ApptEventPublisher.ApptStateChanged e) {
        if (e == null || e.apptNo() == null) {
            return;
        }
        consumed.incrementAndGet();

        String prev = lastState.put(e.apptNo(), e.to());
        if (prev != null && e.from() != null && !prev.equals(e.from())) {
            // 上一条事件的终点，应该等于这一条事件的起点。对不上就是乱序。
            long n = outOfOrder.incrementAndGet();
            log.error("预约状态事件乱序：apptNo={} 上次结束于 {}，本次却从 {} 开始（累计 {} 次）。"
                    + "检查生产端是否还在用 syncSendOrderly + 凭证号作 sharding key，"
                    + "以及消费端的 consumeMode 是不是被改回 CONCURRENTLY 了",
                    e.apptNo(), prev, e.from(), n);
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

    /** 乱序次数。<b>正常必须恒为 0</b> —— 非零说明顺序消息的两半有一半被改坏了。 */
    public long outOfOrderCount() {
        return outOfOrder.get();
    }
}
