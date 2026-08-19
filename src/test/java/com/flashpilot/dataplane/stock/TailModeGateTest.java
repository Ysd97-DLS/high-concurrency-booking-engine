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
 * <p>这里测的不是 {@link LocalSegmentManager} 本体（它需要 Redis），而是那个判据的**形状**：
 * 「到点了才查、查一次就把下次时间推后、余量够就退出」。
 *
 * <h2>为什么这条值得单独测</h2>
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

    /** 复刻 LocalSegmentManager 里那段判据，输入输出都是数，可直接断言。 */
    private static final class Gate {
        final AtomicLong recheckAt = new AtomicLong(0);
        final AtomicInteger probes = new AtomicInteger();   // 真的"查 Redis"了几次
        boolean tailMode = true;
        long intervalMs;
        int bucketSum;
        int threshold;

        Gate(long intervalMs, int bucketSum, int threshold) {
            this.intervalMs = intervalMs;
            this.bucketSum = bucketSum;
            this.threshold = threshold;
        }

        /** @return true 表示已退出尾部模式 */
        boolean tryLeave(long now) {
            long due = recheckAt.get();
            if (now < due || !recheckAt.compareAndSet(due, now + intervalMs)) {
                return false;
            }
            probes.incrementAndGet();
            if (bucketSum > threshold) {
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
        Gate g = new Gate(1000, 50, 50);
        assertFalse(g.tryLeave(10_000));
        assertTrue(g.tailMode);
        // 超过一个就该退出
        Gate g2 = new Gate(1000, 51, 50);
        assertTrue(g2.tryLeave(10_000));
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
