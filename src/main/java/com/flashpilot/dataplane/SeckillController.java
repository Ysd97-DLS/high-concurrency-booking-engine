package com.flashpilot.dataplane;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;

/**
 * 秒杀接口。
 *
 * <p>{@code holderId} 走请求参数是为了压测方便（wrk 脚本可以直接造不同用户）。
 * 真实系统里当然应该从登录态里取，绝不能让客户端自己报。
 */
@RestController
public class SeckillController {

    private final SeckillService seckillService;
    private final LocalSegmentManager segments;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final InstanceIdentity identity;

    public SeckillController(SeckillService seckillService, LocalSegmentManager segments,
                             StockRedisRepository stockRedis, HotConfigService hotConfig,
                             InstanceIdentity identity) {
        this.seckillService = seckillService;
        this.segments = segments;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.identity = identity;
    }

    @PostMapping("/seckill/{poolId}")
    public ResponseEntity<Map<String, Object>> seckill(@PathVariable long poolId,
                                                       @RequestParam long holderId,
                                                       @RequestParam(required = false) String deviceId) {
        SeckillOutcome outcome = seckillService.seckill(poolId, holderId, deviceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", outcome.code());
        body.put("message", outcome.message());
        body.put("poolId", poolId);
        body.put("holderId", holderId);
        // 售罄和限流都不是服务端错误，用 200 返回业务码，避免压测工具把它们统计成 error
        return ResponseEntity.ok(body);
    }

    /** 排查用：看这个实例当前的库存视图。压测时开着它很有用。 */
    @GetMapping("/seckill/state/{poolId}")
    public Map<String, Object> state(@PathVariable long poolId) {
        int active = hotConfig.getInt(ConfigParam.ACTIVE_BUCKETS);
        StockRedisRepository.Stats stats = stockRedis.stats(poolId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", identity.id());
        m.put("poolId", poolId);
        m.put("bucketSum", stats.bucketSum());
        m.put("leaseHeldAllInstances", stats.leaseHeld());
        m.put("globalRemaining", stats.total());
        m.put("bucketSkew", String.format("%.3f", stats.skew(active)));
        m.put("activeBuckets", active);
        m.put("localRemaining", segments.localRemaining(poolId));
        m.put("tailMode", segments.tailMode(poolId));
        m.put("segmentHitRatio", String.format("%.4f", segments.segmentHitRatio()));
        m.put("localHits", segments.localHits());
        m.put("refills", segments.refills());
        m.put("steals", segments.steals());
        m.put("anomalies", segments.anomalies());
        return m;
    }
}
