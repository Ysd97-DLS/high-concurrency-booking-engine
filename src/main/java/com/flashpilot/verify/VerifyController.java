package com.flashpilot.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.controlplane.guard.GuardRail;
import com.flashpilot.controlplane.l0.AimdController;
import com.flashpilot.controlplane.l1.AgentLoop;
import com.flashpilot.dataplane.limit.LocalTokenBucket;
import com.flashpilot.clinic.domain.ApptPersistRepository;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.dataplane.stream.ConsumerStats;
import com.flashpilot.clinic.risk.RiskControlService;
import com.flashpilot.clinic.risk.SlowLane;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 实验编排接口。<b>生产系统里不会有这些接口</b>，它们的唯一目的是让压测<i>可重复</i>——
 * 不能重复的实验数据是没有说服力的，而简历上的每个数字都来自这些实验。
 */
@RestController
@RequestMapping("/verify")
public class VerifyController {

    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    private final StockRedisRepository stockRedis;
    private final ApptPersistRepository orders;
    private final ConsumerStats consumerStats;
    private final LocalSegmentManager segments;
    private final LocalTokenBucket limiter;
    private final HotConfigService hotConfig;
    private final GuardRail guard;
    private final AimdController aimd;
    private final AgentLoop agentLoop;
    private final ConsistencyChecker checker;
    private final ExperimentContext experiment;
    private final SeckillMetrics metrics;
    private final RiskControlService riskControl;
    private final SlowLane slowLane;

    public VerifyController(StockRedisRepository stockRedis, ApptPersistRepository orders,
                            ConsumerStats consumerStats, LocalSegmentManager segments,
                            LocalTokenBucket limiter, HotConfigService hotConfig, GuardRail guard,
                            AimdController aimd, AgentLoop agentLoop, ConsistencyChecker checker,
                            ExperimentContext experiment, SeckillMetrics metrics,
                            RiskControlService riskControl, SlowLane slowLane) {
        this.riskControl = riskControl;
        this.slowLane = slowLane;
        this.metrics = metrics;
        this.stockRedis = stockRedis;
        this.orders = orders;
        this.consumerStats = consumerStats;
        this.segments = segments;
        this.limiter = limiter;
        this.hotConfig = hotConfig;
        this.guard = guard;
        this.aimd = aimd;
        this.agentLoop = agentLoop;
        this.checker = checker;
        this.experiment = experiment;
    }

    /**
     * 一键把系统恢复到干净的起点：清订单、清 Redis 库存与租约、清 stream、重置热参数与本地状态。
     *
     * <p>每轮压测前都要调它。顺序有讲究：先清 MySQL 和 stream，再预热 Redis 库存，
     * 最后重置本地状态——否则本地可能还攥着上一轮的号段。
     */
    @PostMapping("/preheat")
    public Map<String, Object> preheat(@RequestParam(defaultValue = "1001") long poolId,
                                       @RequestParam(defaultValue = "1000") int totalStock,
                                       @RequestParam(defaultValue = "8") int buckets) {
        log.info("=== 实验重置 poolId={} totalStock={} buckets={} ===", poolId, totalStock, buckets);

        orders.resetForExperiment(poolId, totalStock);
        consumerStats.reset();
        stockRedis.resetStreamAndGroup();

        hotConfig.resetToDefaults();
        hotConfig.apply(ConfigParam.ACTIVE_BUCKETS, buckets, "MANUAL",
                "实验预热设定活跃桶数", "experiment setup");

        stockRedis.preheat(poolId, totalStock, buckets);
        segments.resetLocal(poolId);
        // 清零全程延迟直方图，否则实验报告会把上一轮的延迟混进来
        metrics.resetRun();
        // 风控计数也要清，否则上一轮的频次会污染这一轮的判定
        riskControl.reset();
        slowLane.reset();
        limiter.reset();
        guard.resetCooldowns();
        aimd.reset();
        agentLoop.reset();
        // 基线要在<b>清完历史预约之后</b>取，和 consumerStats.reset() 处在同一个起点上。
        // 取早了（清库之前）基线偏大，等式⑤ 会一直报少；取晚了没影响，但必须在压测开始前。
        experiment.begin(poolId, totalStock, orders.countAllApptsGlobal());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("round", experiment.round());
        m.put("poolId", poolId);
        m.put("totalStock", totalStock);
        m.put("activeBuckets", buckets);
        m.put("config", hotConfig.snapshot());
        m.put("agentEnabled", agentLoop.enabled());
        m.put("riskBlocklistSize", riskControl.blocklistSize());
        m.put("riskCmsMemoryKb", riskControl.cmsMemoryBytes() / 1024);
        m.put("riskThreshold", hotConfig.get(ConfigParam.RISK_THRESHOLD));
        m.put("hint", "现在可以开始压测。压完调 GET /verify/check 看一致性。");
        return m;
    }

    /** 一致性校验。压测结束、消费者追平之后调，否则等式 ④ 会显示还有未处理消息。 */
    @GetMapping("/check")
    public ConsistencyChecker.Report check() {
        return checker.check();
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "10") int limit) {
        return checker.history(limit);
    }
}
