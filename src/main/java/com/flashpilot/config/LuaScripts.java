package com.flashpilot.config;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 把 {@code resources/lua/} 下的脚本加载成 {@link RedisScript} bean。
 *
 * <p>Spring 会自动走 EVALSHA，只有服务端没缓存这个 SHA 时才回退到 EVAL 把脚本体传过去，
 * 所以不用担心每次调用都在网络上传一遍脚本。
 */
@Component
public class LuaScripts {

    /** 领号段：扣首选桶 + 借调，返回 {got, stolen, remaining}。 */
    public final RedisScript<List> takeSegment = list("take_segment.lua");

    /** 号段模式卖出一件：XADD + 租约扣 1，返回 {code, streamId}。 */
    public final RedisScript<List> sellOne = list("sell_one.lua");

    /** 尾部模式卖出一件：直接扣桶 + XADD，返回 {code, streamId, remaining}。 */
    public final RedisScript<List> sellOneTail = list("sell_one_tail.lua");

    /** 优雅归还未售号段，返回实际归还数量。 */
    public final RedisScript<Long> returnSegment = number("return_segment.lua");

    /** 回收过期租约，返回 {reclaimed, instanceCount}。 */
    public final RedisScript<List> reclaimLeases = list("reclaim_leases.lua");

    /** 库存全局快照，返回 {bucketSum, leaseHeld, instanceCount, b1..bn}。 */
    public final RedisScript<List> stats = list("stats.lua");

    /** 幂等创建消费组（带 MKSTREAM）。 */
    public final RedisScript<Long> ensureGroup = number("ensure_group.lua");

    /** 实验重置：清空 stream 但保留消费组，并 ACK 掉残留的 pending。 */
    public final RedisScript<Long> resetStream = number("reset_stream.lua");

    /** 预约取消 / 超时后把号源还回号池。注意它<b>不碰租约</b>，与 returnSegment 语义不同。 */
    @SuppressWarnings("rawtypes")
    public final RedisScript<List> releaseSlots = list("release_slots.lua");
    public final RedisScript<List> spreadSlots = list("spread_slots.lua");

    /** XAUTOCLAIM 抢回长时间未 ACK 的消息，返回拍平的 {id, holderId, poolId, ...}。 */
    public final RedisScript<List> claimPending = list("claim_pending.lua");

    private static RedisScript<List> list(String file) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + file));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<Long> number(String file) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + file));
        script.setResultType(Long.class);
        return script;
    }
}
