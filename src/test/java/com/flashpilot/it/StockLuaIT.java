package com.flashpilot.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flashpilot.dataplane.stock.StockRedisRepository;

/**
 * 三层库存的 Lua 脚本 —— 真 Redis 上跑。
 *
 * <p>这是 README 里自认缺口的第一处：{@code sell_one.lua} 那句
 * {@code HINCRBY + XADD} 是超卖与少卖的分界线，而它此前<b>没有任何自动化测试</b>，
 * 只能靠压测端到端验证。端到端失败时只知道「少了 3 个」，不知道是哪一层少的。
 *
 * <p>这里验的都是<b>并发下的不变量</b>，不是「能不能调通」——
 * 单线程调通对这类脚本毫无信息量，它们存在的全部理由就是并发正确。
 */
@DisplayName("集成：三层库存的 Lua 脚本")
class StockLuaIT extends IntegrationBase {

    @Autowired
    StockRedisRepository stock;

    private static final long POOL = 990001L;

    @BeforeEach
    void reset() {
        // 每个测试自己保证起点干净 —— 容器是 JVM 内共享的，不能假设它是空的。
        stock.preheat(POOL, 0, 8);
    }

    @Test
    @DisplayName("并发扣减不超卖：100 线程抢 50 个号，成功数恰好 50")
    void neverOversell() throws Exception {
        stock.preheat(POOL, 50, 8);

        int threads = 100;
        AtomicInteger ok = new AtomicInteger();
        runConcurrently(threads, () -> {
            // 直接打 Lua 脚本这一层，绕开限流和风控 ——
            // 这个测试要钉的是脚本的原子性，掺进上层判据会让失败原因变得含糊。
            if (stock.sellOneTail(POOL, 0, 1L).ok()) {
                ok.incrementAndGet();
            }
            return null;
        });

        assertThat(ok.get())
                .as("成功扣减数必须恰好等于库存 —— 多一个是超卖，少一个是脚本丢了扣减")
                .isEqualTo(50);
        assertThat(stock.stats(POOL).total()).as("扣完之后全局余量必须归零").isZero();
    }

    @Test
    @DisplayName("扣减与事件投递是原子的：成交数 == Stream 长度")
    void deductAndPublishAreAtomic() throws Exception {
        stock.preheat(POOL, 30, 8);
        long before = stock.streamLength();

        AtomicInteger ok = new AtomicInteger();
        runConcurrently(60, () -> {
            if (stock.sellOneTail(POOL, 0, 1L).ok()) {
                ok.incrementAndGet();
            }
            return null;
        });

        // 这条断言是整个三层库存设计成立的前提。
        // 一旦有人把 XADD 从 Lua 里挪出去（比如「改用 RocketMQ 投递」），
        // 这里立刻会红 —— 扣了库存而事件没发出去，就是少卖。
        assertThat(stock.streamLength() - before)
                .as("每一次成功扣减必须恰好对应一条成交事件，多一条是超卖、少一条是少卖")
                .isEqualTo(ok.get());
    }

    @Test
    @DisplayName("售罄后继续抢不会扣成负数")
    void noNegativeAfterSoldOut() throws Exception {
        stock.preheat(POOL, 5, 8);
        for (int i = 0; i < 20; i++) {
            stock.sellOneTail(POOL, 0, 1L);
        }
        assertThat(stock.stats(POOL).total())
                .as("余量不能为负 —— 负余量会让号源守恒等式算出假的「多卖」")
                .isNotNegative()
                .isZero();
    }

    @Test
    @DisplayName("桶间借调：库存全压在一个桶里也能全部卖出")
    void borrowAcrossBuckets() throws Exception {
        // 分桶的代价就是可能出现「总量够但你打到的那个桶空了」。
        // 借调是为此存在的，而它是 Lua 里 O(n) 遍历那段 —— 最容易写错的地方。
        stock.preheat(POOL, 40, 8);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        runConcurrently(80, () -> {
            // 每个线程打不同的偏好桶 —— 打同一个桶测不出借调，
            // 因为那样只会命中「自己的桶有货」这条快路径。
            int pref = seq.getAndIncrement() % 8;
            if (stock.sellOneTail(POOL, pref, 1L).ok()) {
                ok.incrementAndGet();
            }
            return null;
        });

        assertThat(ok.get())
                .as("借调必须能把散落在各桶的余量全部卖出，卖不完就是少卖")
                .isEqualTo(40);
    }

    /** 所有线程在同一瞬间放开 —— 错峰执行测不出并发问题。 */
    private static void runConcurrently(int threads, Callable<Void> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
