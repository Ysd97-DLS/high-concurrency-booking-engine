package com.flashpilot.dataplane.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.config.InstanceRegistry;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 租约的两个定时任务，也就是「少卖」防线的两条腿。
 *
 * <ul>
 *   <li><b>心跳续约</b>：证明本实例还活着、手里的库存别被别人收走。</li>
 *   <li><b>过期回收</b>：把已经死掉的实例攥着的库存还回桶。</li>
 * </ul>
 *
 * <p>回收任务在<b>每个</b>实例上都跑，靠 Lua 脚本的原子性保证不会重复归还——
 * 这比选主（谁来当回收者）简单得多，也少一个故障点。
 */
@Component
public class LeaseMaintainer {

    private static final Logger log = LoggerFactory.getLogger(LeaseMaintainer.class);

    private final StockRedisRepository stockRedis;
    private final LocalSegmentManager segments;
    private final InstanceIdentity identity;
    private final FlashPilotProperties props;
    private final SeckillMetrics metrics;
    private final InstanceRegistry registry;

    public LeaseMaintainer(StockRedisRepository stockRedis, LocalSegmentManager segments,
                           InstanceIdentity identity, FlashPilotProperties props, SeckillMetrics metrics,
                           InstanceRegistry registry) {
        this.stockRedis = stockRedis;
        this.segments = segments;
        this.identity = identity;
        this.props = props;
        this.metrics = metrics;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${flashpilot.stock.heartbeat-ms:3000}")
    public void heartbeat() {
        // 顺带登记「本实例还活着」并检查判重模式和部署形态是否匹配。
        // 搭在这个任务上而不是新开一个 @Scheduled：调度池的「size >= 任务数」不变量
        // 已经漂移过一次（注释写 8、实际 14），能不加任务就不加。
        registry.registerAndCheck();

        long expireAt = System.currentTimeMillis() + props.stock().leaseTtlMs();
        for (Long poolId : segments.knownItemIds()) {
            if (segments.localRemaining(poolId) <= 0) {
                continue;
            }
            try {
                stockRedis.renewLease(poolId, identity.id(), expireAt);
            } catch (Exception e) {
                log.warn("租约续约失败 poolId={}：{}", poolId, e.toString());
            }
        }
    }

    @Scheduled(fixedDelayString = "${flashpilot.stock.reclaim-scan-ms:2000}")
    public void reclaimExpired() {
        for (Long poolId : segments.knownItemIds()) {
            try {
                StockRedisRepository.Reclaim r = stockRedis.reclaimExpired(poolId);
                if (r.reclaimed() > 0) {
                    metrics.leaseReclaimed(r.reclaimed());
                    log.warn("回收过期租约 poolId={} 归还={}件 涉及实例={}个 —— 这些库存本来会变成少卖",
                            poolId, r.reclaimed(), r.instances());
                }
            } catch (Exception e) {
                log.warn("租约回收扫描失败 poolId={}：{}", poolId, e.toString());
            }
        }
    }
}
