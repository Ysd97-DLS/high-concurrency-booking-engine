package com.flashpilot.verify;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 当前正在做实验的商品。
 *
 * <p>控制面和一致性校验器都需要知道「盯哪个商品」。生产系统里这会是一个活动编排模块，
 * 这里简化成一个可变的上下文，由 {@code POST /verify/preheat} 设定。
 */
@Component
public class ExperimentContext {

    private final AtomicLong poolId = new AtomicLong(1001L);
    private final AtomicInteger totalStock = new AtomicInteger(1000);
    private final AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong label = new AtomicLong(0);

    public long poolId() {
        return poolId.get();
    }

    public int totalStock() {
        return totalStock.get();
    }

    public long startedAt() {
        return startedAt.get();
    }

    /** 实验轮次编号，方便把多轮压测的报告区分开。 */
    public long round() {
        return label.get();
    }

    /**
     * 本次进程启动后是否做过 preheat。
     *
     * <p>一致性校验里的<b>累计量</b>等式需要它。「消费入库数」存在 Redis
     * （{@code ConsumerStats} → {@code fp:stat:consumed}），「已落库预约数」在 MySQL，
     * <b>两个存储各自被清空的时机完全无关</b>：清 Redis 不会动 MySQL，反之亦然。
     * 所以只有在 preheat 划定了共同起点之后（preheat 会 DELETE 该号池的历史预约，
     * 同时 reset 计数器，两边一起归零）这两个数才可比。
     *
     * <p>没有这个判据会出现一个很有误导性的现象：清过 Redis 之后随便挂一个号，
     * 校验就报「落库预约 24053 == 消费入库 1 不一致」——数据其实完全正确，
     * 只是拿一个从未清过的累计值去比一个刚被清零的累计值。
     * <b>而一个平时就是红的校验报告等于没有报告</b>，真出问题时没人会注意。
     *
     * <p>（这里最初写的是「消费入库数是进程内内存计数器、重启即归零」，那是错的——
     * 它一直在 Redis 里，跨重启保留。当时看到 consumed=1 是因为 Redis 被清过，
     * 而我用 {@code KEYS "stat:*"} 去查，没匹配上带前缀的 {@code fp:stat:*}，
     * 于是得出了错误的归因。结论侥幸对了，理由是错的——记在这里免得下次照着错的理由推。）
     */
    public boolean preheated() {
        return label.get() > 0;
    }

    /**
     * 本轮开始时全库的预约行数。
     *
     * <p>等式⑤ 的累计量组用它做基线：{@code 全局行数 − 基线 == 消费入库数}。
     * 因为「消费入库数」是全局计数器（Stream 只有一条，所有号池共用），
     * 只有两边都取全局才是同一口径。而减基线是为了容忍库里的历史数据
     * （preheat 只清当前号池，别的号池的预约本就该留着）。
     */
    private final AtomicLong apptBaseline = new AtomicLong(0);

    public long apptBaseline() {
        return apptBaseline.get();
    }

    public void begin(long newItemId, int newTotalStock, int globalApptCount) {
        poolId.set(newItemId);
        totalStock.set(newTotalStock);
        startedAt.set(System.currentTimeMillis());
        apptBaseline.set(globalApptCount);
        label.incrementAndGet();
    }
}
