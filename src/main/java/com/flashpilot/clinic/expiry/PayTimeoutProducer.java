package com.flashpilot.clinic.expiry;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 给每张待支付预约单投递一条「到期来看一眼」的定时消息。
 *
 * <p>投递失败<b>不抛异常、不回滚</b>：预约单已经落库了，消息只是让释放更及时。
 * 发不出去的最坏结果是这张单等定时扫描来处理，而扫描本来就一直在跑。
 * <b>让一条「优化路径」的失败去回滚一条「主业务」是本末倒置。</b>
 */
@Component
public class PayTimeoutProducer {

    private static final Logger log = LoggerFactory.getLogger(PayTimeoutProducer.class);

    /** 没配 name-server 时为 null，此时超时释放退化成纯定时扫描。 */
    private final RocketMQTemplate rocketmq;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /**
     * 用 {@link ObjectProvider} 而不是 {@code @ConditionalOnBean(RocketMQTemplate.class)}。
     *
     * <p>这里踩过一个坑，值得记：{@code @ConditionalOnBean} 挂在普通 {@code @Component} 上
     * <b>是不可靠的</b> —— 条件在组件扫描阶段评估，而 {@code RocketMQTemplate} 来自
     * 自动配置，那时还不存在，于是条件恒为 false、这个 bean 根本不会被创建。
     *
     * <p>更糟的是它<b>不报错</b>：上游用 {@code ObjectProvider.getIfAvailable()} 拿不到就跳过投递，
     * 一切看起来正常运行，只是定时消息一条都没发出去 ——
     * 排查时 broker 上连 topic 都没有，而应用日志一片干净。
     * <b>我为「没起 RocketMQ 也能跑」写的优雅降级，恰好把接线错误藏了起来。</b>
     */
    public PayTimeoutProducer(ObjectProvider<RocketMQTemplate> template) {
        RocketMQTemplate t;
        try {
            t = template.getIfAvailable();
        } catch (Exception e) {
            // getIfAvailable() 只在「bean 不存在」时返回 null；bean 存在但**初始化失败**
            // （比如 name-server 配成空串）时它会把异常向上抛。
            //
            // 不接住的后果是整个应用起不来 —— 而这条链路的全部意义只是让释放更及时，
            // 定时扫描一直都在。**让一个可选优化的配置错误阻止应用启动是本末倒置**，
            // 和「投递失败不回滚已落库的预约单」是同一条原则。
            t = null;
            log.warn("RocketMQ 初始化失败，支付超时释放退化为纯定时扫描：{}", e.toString());
        }
        this.rocketmq = t;
        if (rocketmq == null) {
            log.warn("支付超时定时消息未启用 —— 释放会晚最多一个扫描周期，正确性不受影响");
        } else {
            log.info("支付超时定时消息已启用，topic={}", PayTimeoutMessaging.TOPIC);
        }
    }

    /**
     * 批量投递。由消费者攒批落库成功后调用。
     *
     * @param apptNos  本批真正插进库的凭证号
     * @param deadline 这批单的支付时限
     */
    public void scheduleAll(List<String> apptNos, LocalDateTime deadline) {
        if (rocketmq == null || apptNos.isEmpty()) {
            return;
        }
        long deliverAt = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (String apptNo : apptNos) {
            schedule(apptNo, deliverAt);
        }
    }

    private void schedule(String apptNo, long deliverAtMs) {
        try {
            // syncSendDeliverTimeMills 是 5.x 的定时消息：投递时刻精确到毫秒，
            // 不是 4.x 那 18 个固定延迟级别。支付时限可配（压测时会调到 1 分钟），
            // 固定级别刚好卡不住。
            rocketmq.syncSendDeliverTimeMills(
                    PayTimeoutMessaging.TOPIC,
                    MessageBuilder.withPayload(new PayTimeoutMessaging.PayTimeout(apptNo)).build(),
                    deliverAtMs);
            sent.incrementAndGet();
        } catch (Exception e) {
            long n = failed.incrementAndGet();
            // 只在头几次和每千次打日志：投递失败时往往是整批失败（broker 挂了），
            // 每条打一行会把日志刷爆，而这条链路本来就有兜底，不值得刷屏。
            if (n <= 3 || n % 1000 == 0) {
                log.warn("支付超时定时消息投递失败（第 {} 次）apptNo={}：{} —— "
                        + "不影响正确性，这张单会由定时扫描兜底释放", n, apptNo, e.toString());
            }
        }
    }

    public long sentCount() {
        return sent.get();
    }

    public long failedCount() {
        return failed.get();
    }
}
