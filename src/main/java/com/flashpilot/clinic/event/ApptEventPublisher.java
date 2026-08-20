package com.flashpilot.clinic.event;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 预约单状态变更的领域事件 —— <b>顺序消息</b>。
 *
 * <h2>为什么这条链路必须是顺序消息</h2>
 *
 * 同一张预约单的状态变更是一条<b>有先后的因果链</b>：
 * {@code PENDING_PAY → BOOKED → REFUNDED}。下游按这些事件重建状态或做统计时，
 * 顺序错了结论就错了 —— 先收到 REFUNDED 再收到 BOOKED，
 * 会得出「这个号先被退了、然后又被预约上了」，而那从来没发生过。
 *
 * <p>普通消息在 RocketMQ 里会被分散到多个队列并发消费，<b>不保证顺序</b>。
 * 所以这里用 {@code syncSendOrderly} 并以<b>凭证号</b>作为 sharding key：
 * 同一张单的所有事件落进同一个队列，队列内 FIFO，消费端也按队列串行消费。
 *
 * <h2>为什么 sharding key 是凭证号而不是排班号</h2>
 *
 * 用排班号会把同一个号池的几万张单全压进一个队列 —— 顺序是保住了，
 * 但并发度掉到 1，热点排班的事件会堆积。而顺序要求本来就<b>只存在于单张单内部</b>：
 * A 单的退号和 B 单的支付谁先谁后，对任何下游都无所谓。
 *
 * <p><b>顺序消息的代价要说清楚</b>：同一队列内消费是串行的，一条消息卡住会阻塞它后面
 * 所有属于同一队列的消息。所以消费端必须保证不会长时间卡住 ——
 * 这也是为什么这条链路刻意只做<b>轻量的审计留痕</b>，不在消费端做重活。
 *
 * <h2>投递失败为什么不回滚</h2>
 *
 * 状态已经在 MySQL 里改完了，这些事件是<b>派生信息</b>。发不出去的后果是
 * 审计流水少一条，而不是业务出错。让一条派生信息的投递失败去回滚一次真实的
 * 状态变更（比如把已经退掉的号再改回已预约），是本末倒置 ——
 * 和支付超时那条链路是同一条原则。
 */
@Component
public class ApptEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ApptEventPublisher.class);

    /** 主题。tag 用状态名，下游可以按 tag 只订阅自己关心的那几种。 */
    public static final String TOPIC = "fp-appt-event";

    private final RocketMQTemplate rocketmq;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public ApptEventPublisher(ObjectProvider<RocketMQTemplate> template) {
        RocketMQTemplate t;
        try {
            t = template.getIfAvailable();
        } catch (Exception e) {
            // 同 PayTimeoutProducer：getIfAvailable() 在 bean 初始化失败时会向上抛，
            // 不接住会让整个应用起不来 —— 而这条链路只是派生信息。
            t = null;
            log.warn("RocketMQ 初始化失败，预约状态事件不发布：{}", e.toString());
        }
        this.rocketmq = t;
    }

    /**
     * 发布一次状态变更。
     *
     * @param apptNo   凭证号，同时是 sharding key —— 同一张单的事件保证有序
     * @param from     变更前状态
     * @param to       变更后状态
     * @param reason   变更原因（人读的，用于审计流水）
     */
    public void publish(String apptNo, String from, String to, String reason) {
        if (rocketmq == null || apptNo == null) {
            return;
        }
        try {
            ApptStateChanged payload = new ApptStateChanged(apptNo, from, to, reason,
                    System.currentTimeMillis());
            // destination 里的 :tag 让下游能按状态过滤订阅，
            // 不必收下全部事件再在消费端 if-else 挑一遍。
            rocketmq.syncSendOrderly(
                    TOPIC + ":" + to,
                    MessageBuilder.withPayload(payload).build(),
                    apptNo);                      // ← sharding key
            published.incrementAndGet();
        } catch (Exception e) {
            long n = failed.incrementAndGet();
            if (n <= 3 || n % 1000 == 0) {
                log.warn("预约状态事件发布失败（第 {} 次）apptNo={} {}->{}：{} —— "
                        + "状态已在库里改完，这里只影响审计流水", n, apptNo, from, to, e.toString());
            }
        }
    }

    public long publishedCount() {
        return published.get();
    }

    public long failedCount() {
        return failed.get();
    }

    /** 状态变更事件。 */
    public record ApptStateChanged(
            String apptNo, String from, String to, String reason, long atMs) {
    }
}
