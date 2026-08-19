package com.flashpilot.clinic.risk;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;

/**
 * 慢车道：被风控降权的请求走这里。
 *
 * <p><b>为什么需要独立的桶，而不是让降权请求「多过一道主限流」。</b>
 * 第一版就是那么做的，实测发现它在低负载下完全没效果：主令牌桶不饱和时，
 * 连过两道和过一道没有区别，黄牛照样 100% 抢到。
 *
 * <p>我起初认为「没有竞争时不该惩罚任何人」是合理的，但这个推理在挂号场景是错的——
 * <b>号源是有限的</b>。黄牛在平峰期用一台设备刷走 40 个号，这些号就真的没了，
 * 跟当时有没有别人在抢毫无关系。所以降权必须与整体负载无关地生效。
 *
 * <p>做法是给降权流量一条<b>绝对速率很低的独立车道</b>：
 * 速率取主限流的一个小比例，且有独立的桶。这样无论系统闲还是忙，
 * 降权流量每秒最多只能通过这么多——它仍然有机会抢到（不是硬拒），
 * 但拿到大量号源在物理上就不可能了。
 */
@Component
public class SlowLane {

    /**
     * 突发容量刻意很小：黄牛脚本的特征就是瞬间打一大批，不给它攒突发的空间。
     * 但也不能是 1 —— 那样连正常的双击都过不去。
     */
    private static final double BURST = 5.0;

    private record State(long nanos, double tokens) {
    }

    private final HotConfigService hotConfig;
    private final AtomicReference<State> state =
            new AtomicReference<>(new State(System.nanoTime(), BURST));
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public SlowLane(HotConfigService hotConfig) {
        this.hotConfig = hotConfig;
    }

    /** 降权请求尝试通行。 */
    public boolean tryAcquire() {
        // 绝对速率，与主限流无关 —— 这是「降权」语义的要求，见 ConfigParam.SLOW_LANE_QPS
        double rate = hotConfig.get(ConfigParam.SLOW_LANE_QPS);
        while (true) {
            State current = state.get();
            long now = System.nanoTime();
            double elapsed = Math.max(0, now - current.nanos()) / 1_000_000_000.0;
            double tokens = Math.min(BURST, current.tokens() + elapsed * rate);
            if (tokens < 1.0) {
                if (state.compareAndSet(current, new State(now, tokens))) {
                    dropped.incrementAndGet();
                    return false;
                }
                continue;
            }
            if (state.compareAndSet(current, new State(now, tokens - 1.0))) {
                passed.incrementAndGet();
                return true;
            }
        }
    }

    public long passedCount() {
        return passed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public void reset() {
        state.set(new State(System.nanoTime(), BURST));
        passed.set(0);
        dropped.set(0);
    }
}
