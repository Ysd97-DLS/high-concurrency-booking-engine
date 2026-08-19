package com.flashpilot.dataplane.stream;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 消费侧的计数器，放在 Redis 里而不是进程内存，因为一致性校验要汇总<b>所有实例</b>的结果。
 *
 * <p>这几个数字是链路守恒等式的组成部分：
 * <pre>
 *   XLEN(stream) == 已入库 + 重复跳过 + 超卖拦截 + 死信
 * </pre>
 * 等式不成立就说明消息在某个环节被吞了，这比「感觉没问题」可靠得多。
 */
@Component
public class ConsumerStats {

    private static final String CONSUMED = "fp:stat:consumed";
    private static final String DUPLICATE = "fp:stat:duplicate";
    private static final String DEAD_LETTER = "fp:stat:deadletter";
    private static final String OVERSOLD_BLOCKED = "fp:stat:oversoldBlocked";
    private static final String CLAIMED = "fp:stat:claimed";
    private static final String SUBSCRIPTION_RESTARTS = "fp:stat:subRestarts";

    private final StringRedisTemplate redis;

    public ConsumerStats(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void consumed() {
        redis.opsForValue().increment(CONSUMED);
    }

    /**
     * 看门狗重建了一次订阅。
     *
     * <p>这个计数不为 0 就说明发生过「消费者静默停工」——
     * 监听容器在 Redis 连接抖动时会取消订阅且不自愈。它是可观测性里很重要的一个信号，
     * 因为这种故障不报错、不重启，应用看起来完全健康。
     */
    public void subscriptionRestarted() {
        redis.opsForValue().increment(SUBSCRIPTION_RESTARTS);
    }

    public long subscriptionRestartCount() {
        return read(SUBSCRIPTION_RESTARTS);
    }

    public void duplicate() {
        redis.opsForValue().increment(DUPLICATE);
    }

    public void deadLetter() {
        redis.opsForValue().increment(DEAD_LETTER);
    }

    /** MySQL 的 {@code sold_stock < total_stock} 挡下了一次超卖。正常情况下必须是 0。 */
    public void oversoldBlocked() {
        redis.opsForValue().increment(OVERSOLD_BLOCKED);
    }

    public void claimed(long count) {
        redis.opsForValue().increment(CLAIMED, count);
    }

    public long consumedCount() {
        return read(CONSUMED);
    }

    public long duplicateCount() {
        return read(DUPLICATE);
    }

    public long deadLetterCount() {
        return read(DEAD_LETTER);
    }

    public long oversoldBlockedCount() {
        return read(OVERSOLD_BLOCKED);
    }

    public long claimedCount() {
        return read(CLAIMED);
    }

    public void reset() {
        redis.delete(List.of(CONSUMED, DUPLICATE, DEAD_LETTER, OVERSOLD_BLOCKED, CLAIMED,
                SUBSCRIPTION_RESTARTS));
    }

    private long read(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0L : Long.parseLong(v);
    }
}
