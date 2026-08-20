package com.flashpilot.clinic.risk;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.flashpilot.clinic.risk.mapper.RiskEventMapper;

/**
 * 风控审计记录的投递与落库。
 *
 * <h2>为什么这条链路可以走 MQ，而抢号链路不行</h2>
 *
 * 我一开始判断错了，理由是「风控在 3 万 req/s 的热路径上，加一次 broker 往返
 * 等于把本地号段省下来的 RTT 还回去」。<b>那句话把两件事混为一谈了</b>：
 * 事件<i>产生</i>在热路径上，但<i>投递</i>早就不在了 ——
 * 热路径只做 {@code queue.add}（纯内存），落库由每 2 秒一次的定时任务攒批做。
 *
 * <p>所以把 flush 的目标从 MySQL 换成 broker，<b>热路径一行都不用改</b>，
 * 每 2 秒才发一条消息（一批几百条记录打包成一个消息体），开销可以忽略。
 *
 * <p>对比之下抢号链路是真的不能换：那里的 {@code XADD} 写在 {@code sell_one.lua} 里、
 * 和扣租约是同一个原子操作，拆开就是双写问题。
 * <b>两条链路的区别不是「热不热」，是「投递是否必须和扣减原子」。</b>
 *
 * <h2>换成 MQ 解决了什么真问题</h2>
 *
 * 原来 flush 直接写 MySQL，而入队处有一道 {@code if (queue.size() < 20_000)} 的闸门 ——
 * MySQL 一慢，队列涨到 2 万就开始<b>静默丢弃审计记录</b>。
 * 而队列最可能被打满的时刻，恰好是黄牛正在攻击、审计记录最有价值的时刻。
 *
 * <p>投给 broker 之后，MySQL 慢不再回压到应用内存：消息堆在 broker 上，
 * 消费端慢慢追。丢弃闸门仍然保留（broker 也可能挂），但触发概率大幅下降，
 * 而且真触发时有计数和告警（见 {@code RiskControlService.eventsDropped}）。
 */
@Component
public class RiskEventMessaging {

    private static final Logger log = LoggerFactory.getLogger(RiskEventMessaging.class);

    public static final String TOPIC = "fp-risk-event";
    public static final String CONSUMER_GROUP = "fp-risk-event-persist";

    private final RocketMQTemplate rocketmq;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong sendFailed = new AtomicLong();

    public RiskEventMessaging(ObjectProvider<RocketMQTemplate> template) {
        RocketMQTemplate t;
        try {
            t = template.getIfAvailable();
        } catch (Exception e) {
            t = null;
            log.warn("RocketMQ 初始化失败，风控事件退回直接落库：{}", e.toString());
        }
        this.rocketmq = t;
    }

    /** MQ 是否可用。不可用时调用方直接落库 —— 审计记录不能因为中间件缺席就丢。 */
    public boolean available() {
        return rocketmq != null;
    }

    /**
     * 投递一批风控事件。
     *
     * @return true 表示已交给 broker；false 表示调用方需要自己落库
     */
    public boolean publish(List<RiskEventMapper.RiskEvent> batch) {
        if (rocketmq == null || batch.isEmpty()) {
            return false;
        }
        try {
            // 整批打成<b>一个</b>消息，不是一条记录一个消息。
            // 一批几百条、每 2 秒一次，broker 侧的消息数因此低两个数量级，
            // 而这些记录本来就是一起产生、一起消费的，没有单条投递的必要。
            rocketmq.syncSend(TOPIC, MessageBuilder.withPayload(new RiskEventBatch(batch)).build());
            sent.incrementAndGet();
            return true;
        } catch (Exception e) {
            long n = sendFailed.incrementAndGet();
            if (n <= 3 || n % 100 == 0) {
                log.warn("风控事件投递失败（第 {} 次，本批 {} 条），改为直接落库：{}",
                        n, batch.size(), e.toString());
            }
            return false;
        }
    }

    public long sentBatches() {
        return sent.get();
    }

    public long sendFailedBatches() {
        return sendFailed.get();
    }

    /** 一批风控事件。 */
    public record RiskEventBatch(List<RiskEventMapper.RiskEvent> events) {
    }

    /**
     * 消费端：把一批风控事件落库。
     *
     * <p>没有做去重。风控事件是<b>行为流水</b>而不是账目 ——
     * 重复消费导致同一条命中记录出现两次，对运营判断几乎无影响；
     * 而为它加一张去重表、每条查一次，代价远大于收益。
     * 这和号源那条链路的取舍完全相反，因为<b>那边重复一次就是超卖</b>。
     */
    @Component
    @RocketMQMessageListener(topic = TOPIC, consumerGroup = CONSUMER_GROUP)
    public static class Consumer implements RocketMQListener<RiskEventBatch> {

        private static final Logger clog = LoggerFactory.getLogger(Consumer.class);

        private final RiskEventMapper mapper;
        private final AtomicLong persisted = new AtomicLong();

        public Consumer(RiskEventMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void onMessage(RiskEventBatch batch) {
            if (batch == null || batch.events() == null || batch.events().isEmpty()) {
                return;
            }
            try {
                mapper.insertBatch(batch.events());
                persisted.addAndGet(batch.events().size());
            } catch (Exception e) {
                // 抛出去让 RocketMQ 重投。审计记录晚落库没关系，丢了才有关系 ——
                // 而这正是从「应用内存队列」换到「broker」要换来的东西：
                // MySQL 短时不可用时消息还在，不像原来直接就丢了。
                clog.warn("风控事件落库失败，将由 RocketMQ 重投（本批 {} 条）：{}",
                        batch.events().size(), e.toString());
                throw e;
            }
        }

        public long persistedCount() {
            return persisted.get();
        }
    }
}
