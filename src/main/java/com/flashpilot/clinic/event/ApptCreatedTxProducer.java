package com.flashpilot.clinic.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.flashpilot.clinic.domain.AppointmentRepository;

/**
 * 预约落库 + 「预约已创建」事件 —— <b>事务消息</b>。
 *
 * <h2>为什么事务消息只能放在这条链路</h2>
 *
 * RocketMQ 的事务消息<b>与定时消息、顺序消息互斥</b>：半消息在 commit 之前对消费者
 * 不可见，这个机制和「按指定时刻投递」「按队列保序」都无法叠加。而项目里另外两条
 * RocketMQ 链路恰好各占一个：
 *
 * <ul>
 *   <li>支付超时用的是<b>定时消息</b>（到期精确触发）；</li>
 *   <li>状态变更用的是<b>顺序消息</b>（同一张单的迁移必须保序）。</li>
 * </ul>
 *
 * 所以要用事务消息，只能是一条既不要求定时、也不要求保序的链路 ——
 * 「预约已创建」正好符合：它是每张单的第一个事件，本身没有前序可乱。
 *
 * <h2>它解决的是什么问题（以及别夸大）</h2>
 *
 * 原来是「先插库、再发消息」，中间有个窗口：插成功而消息没发出去。
 * 事务消息把这个窗口消掉了 —— 消息与落库要么都成立，要么都不成立。
 *
 * <p><b>但要老实说清收益的大小</b>：这条链路上「消息丢了」的后果只是
 * 审计流水缺一条创建记录，不影响号源账目、不影响患者。
 * 所以这更接近<b>用一条低风险链路把事务消息这个能力用对</b>，
 * 而不是「不用它就会出事」。真正不能丢消息的地方（扣库存 → 发成交事件）
 * 反而用不了它，因为那一步的原子性是靠 Lua 在 Redis 内部保证的。
 *
 * <h2>回查为什么在这里是可实现的</h2>
 *
 * 事务消息的难点通常在<b>回查</b>：broker 问「你那个本地事务到底成没成」时，
 * 应用得能查出来。很多系统卡在这里 —— 本地事务没有一个可查询的业务主键。
 *
 * <p>这里天然有：{@code appt_no} 是唯一索引。回查就是
 * 「这批凭证号在库里存在吗」，一条 {@code COUNT(*)} 就能回答。
 */
@Component
public class ApptCreatedTxProducer {

    private static final Logger log = LoggerFactory.getLogger(ApptCreatedTxProducer.class);

    public static final String TOPIC = "fp-appt-created";

    private final RocketMQTemplate rocketmq;
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rolledBack = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();

    public ApptCreatedTxProducer(ObjectProvider<RocketMQTemplate> template) {
        RocketMQTemplate t;
        try {
            t = template.getIfAvailable();
        } catch (Exception e) {
            t = null;
            log.warn("RocketMQ 初始化失败，预约创建走非事务路径：{}", e.toString());
        }
        this.rocketmq = t;
    }

    public boolean available() {
        return rocketmq != null;
    }

    /**
     * 以事务消息的方式落库这一批预约。
     *
     * @return 实际插入的行数；{@code -1} 表示 MQ 不可用，调用方应走原来的直接落库路径
     */
    public int persist(List<AppointmentRepository.PendingAppt> batch) {
        if (rocketmq == null || batch.isEmpty()) {
            return -1;
        }
        List<String> nos = new ArrayList<>(batch.size());
        for (AppointmentRepository.PendingAppt p : batch) {
            nos.add(p.apptNo());
        }
        // 用一个可变的持有者把「本地事务插了几行」带回来 ——
        // sendMessageInTransaction 的返回值是 SendResult，拿不到本地事务的结果。
        LocalTxResult holder = new LocalTxResult(batch);
        Message<ApptCreated> msg = MessageBuilder
                .withPayload(new ApptCreated(nos, System.currentTimeMillis()))
                .build();
        rocketmq.sendMessageInTransaction(TOPIC, msg, holder);
        return holder.inserted;
    }

    /** 本地事务的输入与产出。 */
    public static final class LocalTxResult {
        final List<AppointmentRepository.PendingAppt> batch;
        volatile int inserted = 0;
        volatile boolean executed = false;

        LocalTxResult(List<AppointmentRepository.PendingAppt> batch) {
            this.batch = batch;
        }
    }

    /** 「预约已创建」事件。只带凭证号 —— 回查要用，消费端也够用。 */
    public record ApptCreated(List<String> apptNos, long atMs) {
    }

    void onCommit() {
        committed.incrementAndGet();
    }

    void onRollback() {
        rolledBack.incrementAndGet();
    }

    void onCheck() {
        checked.incrementAndGet();
    }

    public long committedCount() {
        return committed.get();
    }

    public long rolledBackCount() {
        return rolledBack.get();
    }

    /** broker 回查次数。<b>持续非零说明本地事务返回 UNKNOWN 或应用在提交前挂过</b>。 */
    public long checkedCount() {
        return checked.get();
    }

    /**
     * 事务监听器：执行本地事务，以及应答 broker 的回查。
     */
    @RocketMQTransactionListener
    public static class Listener implements RocketMQLocalTransactionListener {

        private static final Logger llog = LoggerFactory.getLogger(Listener.class);

        private final AppointmentRepository appts;
        private final ApptCreatedTxProducer owner;

        public Listener(AppointmentRepository appts, ApptCreatedTxProducer owner) {
            this.appts = appts;
            this.owner = owner;
        }

        @Override
        public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
            if (!(arg instanceof LocalTxResult holder)) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            try {
                holder.inserted = appts.insertPendingBatch(holder.batch);
                holder.executed = true;
                if (holder.inserted > 0) {
                    owner.onCommit();
                    return RocketMQLocalTransactionState.COMMIT;
                }
                // 一行都没插进去（整批都是重复，或凭证号撞了）——
                // 没有新预约诞生，这条「预约已创建」就不该被消费者看到。
                owner.onRollback();
                return RocketMQLocalTransactionState.ROLLBACK;
            } catch (Exception e) {
                // 刻意返回 UNKNOWN 而不是 ROLLBACK：异常可能发生在**插入已经提交之后**
                // （比如提交成功但连接在返回途中断了），这时 ROLLBACK 会让消息永远丢掉，
                // 而库里其实有单。交给回查去查真相 —— 这正是回查存在的意义。
                llog.warn("预约落库本地事务异常，交给 broker 回查：{}", e.toString());
                return RocketMQLocalTransactionState.UNKNOWN;
            }
        }

        @Override
        public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
            owner.onCheck();
            try {
                Object payload = msg.getPayload();
                List<String> nos = extractApptNos(payload);
                if (nos.isEmpty()) {
                    return RocketMQLocalTransactionState.ROLLBACK;
                }
                // 回查的判据就是「这批凭证号在库里存在吗」。
                // appt_no 有唯一索引，这一查是走索引的 COUNT。
                int exists = appts.countExistingApptNos(nos);
                llog.info("事务消息回查：{} 个凭证号中 {} 个已落库 → {}",
                        nos.size(), exists, exists > 0 ? "COMMIT" : "ROLLBACK");
                return exists > 0
                        ? RocketMQLocalTransactionState.COMMIT
                        : RocketMQLocalTransactionState.ROLLBACK;
            } catch (Exception e) {
                // 回查本身失败就返回 UNKNOWN，让 broker 过一会儿再问。
                // 返回 ROLLBACK 会把一条可能对应着真实预约的消息丢掉。
                llog.warn("事务消息回查失败，等待下次回查：{}", e.toString());
                return RocketMQLocalTransactionState.UNKNOWN;
            }
        }

        private static List<String> extractApptNos(Object payload) {
            if (payload instanceof ApptCreated c) {
                return c.apptNos();
            }
            // 回查时 payload 是从 broker 上取回的原始字节，反序列化成的可能是 Map。
            if (payload instanceof java.util.Map<?, ?> m && m.get("apptNos") instanceof List<?> l) {
                List<String> out = new ArrayList<>(l.size());
                for (Object o : l) {
                    out.add(String.valueOf(o));
                }
                return out;
            }
            if (payload instanceof byte[] b) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b);
                    List<String> out = new ArrayList<>();
                    node.path("apptNos").forEach(n -> out.add(n.asText()));
                    return out;
                } catch (Exception ignored) {
                    return List.of();
                }
            }
            return List.of();
        }
    }
}
