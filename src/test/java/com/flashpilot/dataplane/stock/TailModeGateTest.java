package com.flashpilot.dataplane.stock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 尾部模式重新评估的判据。
 *
 * <p>断言的是 {@link TailModeGate} 本身 —— 也就是 {@link LocalSegmentManager} 生产路径上
 * 真正会执行的那两个纯函数。中间那次 Redis 读取留在调用方，由集成测试覆盖。
 *
 * <h2>这个测试类曾经是「假绿灯」，值得记下来</h2>
 *
 * 原先它在测试类内部<b>复刻</b>了一份同样形状的判据来断言。七条测试全绿，
 * 却与产线代码零耦合：把产线的 {@code bucketSum > tail} 改成 {@code >=}，
 * 一条都不会红。引入 JaCoCo 后这件事一眼可见 —— <b>七条测试通过，而
 * {@code com.flashpilot.dataplane.stock} 的行覆盖是 0</b>。
 * 判据于是被抽进 {@link TailModeGate}，测试改为直接驱动它。
 *
 * <h2>为什么这条判据值得单独测</h2>
 *
 * 尾部模式原来是<b>单向闩锁</b>：桶余量跌破阈值就永久为 true。抽象秒杀域里这是对的
 * —— 库存只减不增。但挂号域会往回加号：退号、超时释放、对账补偿，以及<b>分批放号</b>
 * （这个垂类的招牌功能，持续往桶里加）。
 *
 * <p>实测后果：60 个号、阈值 50，抢掉 20 个进尾部；再补 500 个号，
 * {@code tailMode} 仍是 true，后续 30 次抢号<b>领号段 0 次</b>，命中率从 1.0 掉到 0.5。
 * <b>正确性完全没问题，日志里也没有任何异常</b> —— 只是整套三层库存的性能优势没了，
 * 每次成交都退化成一次 Redis 往返。这类「功能还在、优化没了」的退化最难发现。
 *
 * <p>而重评估本身有个必须防的坑：<b>不能每个请求都去查 Redis</b>。
 * 那等于把「省掉 Redis 往返」彻底做反 —— 本来只有 5% 的请求打 Redis，
 * 变成尾部模式下 100% 的请求都打。所以用 CAS 推进时间窗，
 * 同一个窗口内只有一个线程真的查。
 */
class TailModeGateTest {

    /**
     * 只负责按生产顺序调用 {@link TailModeGate} 的两个函数并数一数探测次数，
     * <b>不复刻任何判据</b> —— 判据全在被测类里。
     */
    private static final class Gate {
        final AtomicLong recheckAt = new AtomicLong(0);
        final AtomicInteger probes = new AtomicInteger();   // 真的"查 Redis"了几次
        boolean tailMode = true;
        final long intervalMs;
        final int bucketSum;
        final int threshold;

        Gate(long intervalMs, int bucketSum, int threshold) {
            this.intervalMs = intervalMs;
            this.bucketSum = bucketSum;
            this.threshold = threshold;
        }

        /** @return true 表示已退出尾部模式 */
        boolean tryLeave(long now) {
            if (!TailModeGate.claimProbe(recheckAt, now, intervalMs)) {
                return false;
            }
            probes.incrementAndGet();                       // 这一步在生产里是一次 stats 调用
            if (TailModeGate.stockRecovered(bucketSum, threshold)) {
                tailMode = false;
                return true;
            }
            return false;
        }
    }

    @Test
    @DisplayName("余量恢复到阈值以上就退出尾部模式")
    void leavesWhenStockRecovers() {
        Gate g = new Gate(1000, 521, 50);
        assertTrue(g.tryLeave(10_000));
        assertFalse(g.tailMode);
    }

    @Test
    @DisplayName("余量仍然不足就留在尾部模式")
    void staysWhenStockStillLow() {
        Gate g = new Gate(1000, 30, 50);
        assertFalse(g.tryLeave(10_000));
        assertTrue(g.tailMode);
    }

    @Test
    @DisplayName("恰好等于阈值不退出——阈值是「低于等于就进尾部」的闭区间")
    void thresholdIsInclusive() {
        assertFalse(TailModeGate.stockRecovered(50, 50));
        assertTrue(TailModeGate.stockRecovered(51, 50));

        Gate g = new Gate(1000, 50, 50);
        assertFalse(g.tryLeave(10_000));
        assertTrue(g.tailMode);
    }

    @Test
    @DisplayName("时间窗内不重复查 Redis——否则尾部模式下每个请求都打一次，把优化做反了")
    void throttlesProbesWithinWindow() {
        Gate g = new Gate(1000, 30, 50);      // 余量不足，不会退出，方便观察探测次数
        long t = 10_000;
        g.tryLeave(t);                         // 第一次：探测
        for (int i = 0; i < 500; i++) {
            g.tryLeave(t + 1 + i);             // 同一个 1 秒窗口内的 500 次请求
        }
        assertEquals(1, g.probes.get(), "时间窗内重复探测了 " + g.probes.get() + " 次");
    }

    @Test
    @DisplayName("跨过时间窗后允许再探一次")
    void probesAgainAfterWindow() {
        Gate g = new Gate(1000, 30, 50);
        g.tryLeave(10_000);
        g.tryLeave(10_500);                    // 还在窗口内
        assertEquals(1, g.probes.get());
        g.tryLeave(11_000);                    // 窗口刚满
        assertEquals(2, g.probes.get());
    }

    @Test
    @DisplayName("并发下同一个窗口只有一个线程探测")
    void onlyOneThreadProbesPerWindow() throws Exception {
        // CAS 的意义就在这里：高并发下如果每个线程都探测，
        // 尾部模式反而成了 Redis 压力最大的状态。
        Gate g = new Gate(1000, 30, 50);
        int threads = 32;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> g.tryLeave(10_000));
        }
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(1, g.probes.get(), threads + " 个线程里有 " + g.probes.get() + " 个都探测了");
    }

    @Test
    @DisplayName("退出之后不再探测——已经回到号段路径了")
    void noProbeAfterLeaving() {
        Gate g = new Gate(1000, 521, 50);
        assertTrue(g.tryLeave(10_000));
        int after = g.probes.get();
        // 调用方在 tailMode=false 后不会再走这条判据，这里只断言状态是终态
        assertFalse(g.tailMode);
        assertEquals(1, after);
    }
}
