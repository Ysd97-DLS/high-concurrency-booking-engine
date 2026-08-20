package com.flashpilot.clinic.expiry;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import com.flashpilot.clinic.AppointmentService;

/**
 * 定时消息到期：看一眼这张单付了没有，没付就释放号源。
 *
 * <h2>为什么可以安全地做成 at-least-once</h2>
 *
 * RocketMQ 保证至少一次投递，也就是说同一条消息可能被消费多次。
 * 这条链路对重复消费天然免疫，因为释放走的是<b>带旧状态条件的 UPDATE</b>
 * （{@code WHERE status='PENDING_PAY'}）—— 第一次消费改成 EXPIRED 之后，
 * 后续任何一次重复消费的 affected rows 都是 0，直接跳过。
 *
 * <p>这也是消息体里<b>只带凭证号、不带任何状态快照</b>的原因：
 * 消息在途十分钟，期间这张单可能已被支付、退号、或被扫描兜底释放，
 * 随消息带走的状态到期时一定是过时的。回库看当前状态才是唯一正确的做法。
 *
 * <h2>和定时扫描的分工</h2>
 *
 * <ul>
 *   <li><b>定时消息（这里）</b>：主路径。到期精确触发，不轮询、不空扫。</li>
 *   <li><b>{@code SlotReleaseTask} 扫描</b>：兜底。消息丢了、broker 挂了、
 *       消费失败超过重试次数时，由它把漏网的单捞回来。</li>
 * </ul>
 *
 * 这个「快主路径 + 慢兜底」的结构和控制面的
 * 「L0 规则秒级兜底 + L1 Agent 分钟级归因」是同一个模式：
 * <b>主路径负责及时，兜底路径负责完备，两者都不需要同时做到。</b>
 */
@Component
@RocketMQMessageListener(
        topic = PayTimeoutMessaging.TOPIC,
        consumerGroup = PayTimeoutMessaging.CONSUMER_GROUP)
public class PayTimeoutConsumer implements RocketMQListener<PayTimeoutMessaging.PayTimeout> {

    private static final Logger log = LoggerFactory.getLogger(PayTimeoutConsumer.class);

    private final AppointmentService appointments;

    /** 由消息触发的释放数。和扫描兜底的释放数分开计，用来回答「消息这条路到底有没有在干活」。 */
    private final AtomicLong releasedByMessage = new AtomicLong();
    /** 到期时已不是待支付（已付款 / 已退号 / 已被扫描兜底）—— 正常情况，不是错误。 */
    private final AtomicLong alreadySettled = new AtomicLong();

    public PayTimeoutConsumer(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @Override
    public void onMessage(PayTimeoutMessaging.PayTimeout msg) {
        if (msg == null || msg.apptNo() == null || msg.apptNo().isBlank()) {
            // 脏消息直接吃掉。抛异常会让 RocketMQ 重投 16 次再进死信，
            // 而重投一条永远解析不出凭证号的消息毫无意义。
            log.warn("收到空的支付超时消息，忽略");
            return;
        }
        if (appointments.releaseIfStillPending(msg.apptNo())) {
            releasedByMessage.incrementAndGet();
        } else {
            alreadySettled.incrementAndGet();
        }
        // 这里刻意不 catch 异常：数据库真的挂了时应该让消息重投，
        // 而不是把这张单静默丢给兜底扫描（那会把一个基础设施故障藏起来）。
    }

    public long releasedByMessageCount() {
        return releasedByMessage.get();
    }

    public long alreadySettledCount() {
        return alreadySettled.get();
    }
}
