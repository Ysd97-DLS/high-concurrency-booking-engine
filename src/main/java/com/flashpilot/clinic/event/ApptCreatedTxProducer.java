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
 * <h2>为什么本地事务只能答 UNKNOWN（这里返过一个真 bug）</h2>
 *
 * 第一版在 executeLocalTransaction 里「插入行数 &gt; 0 就 COMMIT」—— 而那段代码
 * 跑在调用线程上，插入加入的是<b>外层 persistBatch 的 @Transactional</b>，
 * 返回 COMMIT 时数据库里什么都还没提交。外层随后因超卖拦截整批回滚时
 * （活动尾声的常规路径），一批「从未存在过的预约」的创建事件已经放行给了消费者。
 *
 * <p>所以本地事务对「插入了 &gt; 0 行」只能答 UNKNOWN —— 这批行的命运由外层事务
 * 决定，而外层事务此刻自己都不知道。<b>回查因此从兜底变成了唯一的裁决者</b>，
 * {@code txCheckedByBroker} 与消息量同量级是正常的，不再是异常信号。
 * 代价是每条消息要等 broker 的一轮回查才对消费者可见（秒级），审计流水可以接受。
 *
 * <h2>回查为什么在这里是可实现的</h2>
 *
 * 事务消息的难点通常在<b>回查</b>：broker 问「你那个本地事务到底成没成」时，
 * 应用得能查出来。很多系统卡在这里 —— 本地事务没有一个可查询的业务主键。
 *
 * <p>这里天然有：{@code appt_no} 是唯一索引。回查就是
 * 「这批凭证号在库里存在吗」，一条 {@code COUNT(*)} 就能回答。
 * 查不到行时还要按消息年龄给宽限（见 {@code CHECK_GRACE_MS}）——
 * 「查不到」和「还没提交」在回查的第一眼里长得一模一样。
 *
 * <p><b>残余的重复方向（接受并写明）</b>：整批全是重复时本地事务答 ROLLBACK；
 * 若进程恰好在回执发出前崩溃，回查会查到<b>别的事务早已插入</b>的同名行而放行消息 ——
 * 给已存在的单重发一遍「已创建」。消费方本来就是 at-least-once 语义，按
 * {@code (apptNos, atMs)} 去重即可；比起反方向（丢掉真实预约的事件），这一侧代价小得多。
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
        try {
            rocketmq.sendMessageInTransaction(TOPIC, msg, holder);
        } catch (Exception e) {
            // broker **运行期**不可用时（重启 / OOM / ha compose 里根本没有它），
            // template 不为 null，异常会从这里抛出。不接住的话它会沿
            // insertBatch → persistBatch → flushBatch 一路上抛，逐条重试同样失败，
            // 五次之后记死信并 ACK —— 号已从 Redis 扣走而 MySQL 无单，
            // 正是 P6 丢 19389 个号的同型事故。「MQ 不可用时降级」必须覆盖运行期，
            // 不只是启动期。
            //
            // 但只有**本地事务还没执行过**才能让调用方重插：半消息发送成功后
            // 回执阶段的异常意味着 insert 已经跑过了（在外层事务里），
            // 再插一遍会把这批全部撞成重复。
            if (holder.executed) {
                log.warn("事务消息回执阶段异常（本地事务已执行，插入 {} 行有效，消息交给回查）：{}",
                        holder.inserted, e.toString());
                return holder.inserted;
            }
            log.warn("事务消息发送失败，本批退回直接落库路径：{}", e.toString());
            return -1;
        }
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

    /**
     * broker 回查次数。<b>与消息量同量级是正常的</b>：本地事务加入外层 Spring 事务、
     * 只能答 UNKNOWN，每条消息都由回查裁决（见类注释）。要盯的是它和
     * {@code committedCount} 的差距持续拉大 —— 那说明回查一直答不出来。
     */
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

        /**
         * 回查宽限期。查不到行且消息比这更年轻时，不敢断言 ROLLBACK ——
         * 插入所在的外层事务可能还没提交（批量插入 + 锁竞争拖过 broker 的首查窗口
         * 是现实的）。比这更老还查不到，外层事务早已结束，查不到就是回滚了。
         */
        static final long CHECK_GRACE_MS = 60_000;

        @Override
        public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
            if (!(arg instanceof LocalTxResult holder)) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            try {
                holder.inserted = appts.insertPendingBatch(holder.batch);
                holder.executed = true;
                if (holder.inserted > 0) {
                    // **不能在这里返回 COMMIT。** 这段代码跑在调用线程上，插入走的是
                    // MyBatis-Spring 的线程绑定事务 —— 它**加入了外层 persistBatch 的
                    // @Transactional，此刻什么都还没提交**。返回 COMMIT 等于在数据库
                    // 提交之前就把消息放行：紧接着 incrementBookedBy 撞上超卖上限、
                    // 整批回滚（活动尾声的常规路径，不是边角），下游就会收到一批
                    // 从未存在过的「预约已创建」。
                    //
                    // 这里唯一诚实的回答是 UNKNOWN：这批行的命运由外层事务决定，
                    // 而外层事务此刻自己都不知道。让回查在事务尘埃落定之后去看真相 ——
                    // 代价是每条消息要等一轮回查才可见（秒级），对审计流水可以接受。
                    return RocketMQLocalTransactionState.UNKNOWN;
                }
                // 一行都没插进去（整批都是重复，或凭证号撞了）。这个结论**不依赖**
                // 外层事务的提交与否 —— 本事务没有插入任何行，无论它提交还是回滚，
                // 这条「预约已创建」都不该被消费者看到。可以立刻 ROLLBACK。
                owner.onRollback();
                return RocketMQLocalTransactionState.ROLLBACK;
            } catch (Exception e) {
                // 刻意返回 UNKNOWN 而不是 ROLLBACK：异常可能发生在**插入已经生效之后**，
                // 这时 ROLLBACK 会让消息永远丢掉，而库里其实有单。交给回查去查真相。
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
                if (exists > 0) {
                    owner.onCommit();
                    llog.info("事务消息回查：{} 个凭证号中 {} 个已落库 → COMMIT", nos.size(), exists);
                    return RocketMQLocalTransactionState.COMMIT;
                }
                // 查不到行 ≠ 一定回滚了：外层事务可能还在路上。按消息年龄给宽限，
                // 直接 ROLLBACK 会把一条马上就要对应真实预约的消息永久丢掉。
                long ageMs = System.currentTimeMillis() - extractAtMs(payload);
                if (ageMs < CHECK_GRACE_MS) {
                    llog.info("事务消息回查：0 个已落库，但消息仅 {}ms 前发出，事务可能未提交 → 再等一轮",
                            ageMs);
                    return RocketMQLocalTransactionState.UNKNOWN;
                }
                owner.onRollback();
                llog.info("事务消息回查：{} 个凭证号 0 个落库且已过宽限期 → ROLLBACK（外层事务回滚了）",
                        nos.size());
                return RocketMQLocalTransactionState.ROLLBACK;
            } catch (Exception e) {
                // 回查本身失败就返回 UNKNOWN，让 broker 过一会儿再问。
                // 返回 ROLLBACK 会把一条可能对应着真实预约的消息丢掉。
                llog.warn("事务消息回查失败，等待下次回查：{}", e.toString());
                return RocketMQLocalTransactionState.UNKNOWN;
            }
        }

        /** 消息发出时刻。取不到时按「刚发出」处理（宁可多等一轮，不可错杀）。 */
        private static long extractAtMs(Object payload) {
            if (payload instanceof ApptCreated c) {
                return c.atMs();
            }
            if (payload instanceof java.util.Map<?, ?> m && m.get("atMs") instanceof Number n) {
                return n.longValue();
            }
            if (payload instanceof byte[] b) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b);
                    if (node.hasNonNull("atMs")) {
                        return node.get("atMs").asLong();
                    }
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return System.currentTimeMillis();
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
