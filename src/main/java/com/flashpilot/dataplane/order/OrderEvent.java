package com.flashpilot.dataplane.order;

/**
 * 一条待落库的成交事件。
 *
 * <p>{@code eventId} 是 Redis Stream 的消息 ID，既用于排查也用于 ACK ——
 * 落库成功之后要拿它去 XACK，顺序不能反：<b>先入库再 ACK</b>。
 * 反过来（先 ACK 再入库）一旦入库失败，消息就从 pending 里消失了，
 * 那是真正的丢单，任何重试机制都救不回来。
 */
public record OrderEvent(long holderId, long poolId, String eventId) {
}
