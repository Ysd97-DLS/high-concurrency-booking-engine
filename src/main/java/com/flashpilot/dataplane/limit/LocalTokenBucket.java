package com.flashpilot.dataplane.limit;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;

/**
 * 进程内令牌桶。速率<b>实时</b>从热配置读，所以控制面调 {@code limit.qps} 是立刻生效的——
 * 这就是整个控制面闭环的执行末端。
 *
 * <p>为什么做在应用里而不是网关：真实系统这一层应该前移到网关甚至 CDN 边缘，
 * 无效流量根本不该进到应用。这里放进应用纯粹是为了让控制面能直接调它、方便做闭环实验，
 * README 里对这个取舍有说明。
 *
 * <p>实现上用「不可变状态 + CAS」而不是加锁：每次调用会分配一个小对象，
 * 代价是年轻代 GC，换来的是没有锁竞争。
 */
@Component
public class LocalTokenBucket {

    private record State(long nanos, double tokens) {
    }

    private final HotConfigService hotConfig;
    private final FlashPilotProperties props;
    private final AtomicReference<State> state;

    public LocalTokenBucket(HotConfigService hotConfig, FlashPilotProperties props) {
        this.hotConfig = hotConfig;
        this.props = props;
        this.state = new AtomicReference<>(new State(System.nanoTime(), props.limit().burst()));
    }

    public boolean tryAcquire() {
        if (!props.limit().enabled()) {
            return true;
        }
        double rate = hotConfig.get(ConfigParam.LIMIT_QPS);
        double burst = Math.max(1, props.limit().burst());

        while (true) {
            State current = state.get();
            long now = System.nanoTime();
            double elapsedSeconds = Math.max(0, now - current.nanos()) / 1_000_000_000.0;
            double tokens = Math.min(burst, current.tokens() + elapsedSeconds * rate);
            if (tokens < 1.0) {
                // 令牌不足。注意不要更新时间戳，否则会把刚积累的令牌抹掉。
                return false;
            }
            if (state.compareAndSet(current, new State(now, tokens - 1.0))) {
                return true;
            }
        }
    }

    /** 压测前重置，让每轮实验从满桶开始。 */
    public void reset() {
        state.set(new State(System.nanoTime(), props.limit().burst()));
    }

    public double currentRate() {
        return hotConfig.get(ConfigParam.LIMIT_QPS);
    }
}
