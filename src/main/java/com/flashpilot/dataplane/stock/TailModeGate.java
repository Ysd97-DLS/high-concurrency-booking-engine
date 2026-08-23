package com.flashpilot.dataplane.stock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 尾部模式重评估的两条判据，从 {@link LocalSegmentManager#tryLeaveTailMode} 里抽出来。
 *
 * <h2>为什么抽出来</h2>
 *
 * 抽出来之前，这段判据只存在于一个私有方法里，而它周围是 Redis 调用、热配置和日志。
 * 于是 {@code TailModeGateTest} 只能在测试类里<b>复刻</b>一份同样形状的逻辑来断言 ——
 * 那份测试跑得再绿，也钉不住产线的这一段：产线的比较符从 {@code >} 改成 {@code >=}，
 * 七条测试一条都不会红。引入 JaCoCo 后这件事直接显形：
 * 该测试类七条全通过，而 {@code com.flashpilot.dataplane.stock} 的行覆盖是 <b>0</b>。
 *
 * <p>所以这里做的不是「为了好看提高覆盖率」，而是把判据挪到<b>产线与测试能共用</b>的位置：
 * 两条方法都是纯函数（输入输出都是数），Redis 调用留在调用方。测试从此断言的是
 * 真正会跑在生产里的那两行。
 *
 * <h2>两条判据各自防的是什么</h2>
 *
 * <ul>
 *   <li>{@link #claimProbe} 防的是「尾部模式下每个请求都去查一次 Redis」——
 *       那会把「省掉 Redis 往返」彻底做反：本来只有约 5% 的请求打 Redis，
 *       变成尾部模式下 100% 都打。CAS 保证同一个时间窗内只有一个线程真的查。</li>
 *   <li>{@link #stockRecovered} 防的是「尾部模式变成单向闩锁」——
 *       抽象秒杀域里库存只减不增，闩锁没问题；挂号域会往回加号（退号、超时释放、
 *       对账补偿、分批放号），闩锁会让号段路径永久失效：功能仍然正确、日志毫无异常，
 *       只是每次成交都退化成一次 Redis 往返。这类「功能还在、优化没了」的退化最难发现。</li>
 * </ul>
 */
public final class TailModeGate {

    private TailModeGate() {
    }

    /**
     * 抢占本轮重评估的探测权。
     *
     * <p>只有「已到重评估时刻」且「CAS 成功推进下一次时刻」的那个线程返回 true，
     * 同一时间窗内的其余线程一律 false、直接走尾部路径。
     *
     * @param recheckAt  下一次允许探测的时刻（毫秒），由调用方持有并被本方法推进
     * @param now        当前时刻（毫秒）
     * @param intervalMs 两次探测的最小间隔
     * @return true 表示由本次调用负责去查 Redis
     */
    public static boolean claimProbe(AtomicLong recheckAt, long now, long intervalMs) {
        long due = recheckAt.get();
        if (now < due) {
            return false;
        }
        return recheckAt.compareAndSet(due, now + intervalMs);
    }

    /**
     * 桶余量是否已回补到可以退出尾部模式。
     *
     * <p><b>严格大于</b>：阈值本身仍算「不足」，与进入尾部模式时的
     * {@code remaining <= tail} 互补，两边合起来对阈值这一点不留缝也不重叠。
     */
    public static boolean stockRecovered(int bucketSum, int threshold) {
        return bucketSum > threshold;
    }
}
