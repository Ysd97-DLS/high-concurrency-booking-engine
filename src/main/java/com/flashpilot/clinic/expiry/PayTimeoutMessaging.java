package com.flashpilot.clinic.expiry;

/**
 * 支付超时释放的消息契约。
 *
 * <h2>为什么这条链路用 RocketMQ，而抢号链路不用</h2>
 *
 * 抢号那条链路的 {@code XADD} 写在 {@code sell_one.lua} 里，和扣租约是
 * <b>同一个原子操作</b>。把投递换成 RocketMQ 就必须把它们拆开，那是经典的双写问题：
 * 扣了库存而消息没发出去是<b>少卖</b>，消息发了而库存没扣是<b>超卖</b>。
 * 所以 Redis Stream 在那条链路上不是「可以替换的中间件」，而是原子性的一部分。
 *
 * <p>支付超时完全是另一回事：它要的是「10 分钟后来看一眼这张单付了没有」——
 * <b>任意时刻的定时投递</b>，而这恰恰是 Redis Stream 做不到、RocketMQ 原生支持的。
 *
 * <h2>为什么不用 4.x 的延迟级别</h2>
 *
 * RocketMQ 4.x 只有 18 个固定延迟级别（1s/5s/…/10m/20m/30m/1h/2h）。
 * 支付时限是可配的（{@code pay-minutes}，默认 10 分钟，压测时会调到 1 分钟
 * 好在一轮压测里把超时释放这条路径压出来），固定级别刚好卡不住这个需求。
 * 5.x 的定时消息可以指定到毫秒，所以选 5.x。
 *
 * <h2>为什么没有用事务消息</h2>
 *
 * 直觉上「插入预约」和「投递定时消息」应该用事务消息保证一致。但这里不需要，
 * 因为<b>定时扫描兜底还在</b>（见 {@code SlotReleaseTask}）：消息丢了最坏结果是
 * 释放晚几秒，由扫描补上。用事务消息换来的是「早几秒释放」，
 * 代价是 half message、回查接口、以及回查逻辑本身出错的可能。
 *
 * <p>这个取舍值得说清楚：<b>当已经存在一条完备但慢的兜底路径时，
 * 主路径只需要「快且大概率对」，不需要「保证一定对」。</b>
 * 反过来如果删掉扫描、只留消息，那才必须上事务消息。
 */
public final class PayTimeoutMessaging {

    /** 定时消息的 topic。 */
    public static final String TOPIC = "fp-pay-timeout";

    /**
     * 消费组。
     *
     * <p>固定成常量而不是配置项：换组名会让 broker 认为这是一个全新的消费者，
     * 从而<b>丢掉已经投递中的定时消息</b> —— 那些消息对应的预约单会一直挂到
     * 定时扫描兜底为止。这个坑不该让部署时的手滑触发。
     */
    public static final String CONSUMER_GROUP = "fp-pay-timeout-consumer";

    private PayTimeoutMessaging() {
    }

    /**
     * 消息体：只带凭证号。
     *
     * <p><b>刻意不带任何状态快照</b>（比如「发消息时是 PENDING_PAY」）。
     * 消息在途十分钟，期间这张单可能已被支付、退号、或被扫描兜底释放，
     * 任何随消息带走的状态在到期时都是过时的。消费端必须回库看当前状态 ——
     * 这正是这条链路能安全地做成 at-least-once 的原因。
     *
     * @param apptNo 预约凭证号
     */
    public record PayTimeout(String apptNo) {
    }
}
