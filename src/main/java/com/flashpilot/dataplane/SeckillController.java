package com.flashpilot.dataplane;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.clinic.auth.PatientIdentity;
import com.flashpilot.config.InstanceIdentity;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 抢号接口 —— 引擎热路径。
 *
 * <h2>{@code holderId} 为什么不再走请求参数</h2>
 *
 * 原来的签名是 {@code seckill(poolId, @RequestParam long holderId, deviceId)}，
 * 代码注释还写着「真实系统里当然应该从登录态里取」—— <b>而它就是那个真实系统的样子</b>。
 * 注释承认了问题却把实现留在原地，实测一个编造的 ID 就能抢到号：
 * <pre>
 * POST /seckill/20016?holderId=999999999
 *   → {"code":200,"message":"抢购成功，订单正在生成"}
 * </pre>
 *
 * <p>真正严重的不是冒充，是<b>风控被整体绕过</b>：CMS 三层频次判据、设备维度阈值、
 * 失约黑名单，全部以 holderId 为计数键。每次请求换一个随机 holderId，
 * 每个 ID 的计数都是 1，<b>永远碰不到任何阈值</b> —— 一个号贩子可以匀速刷空整个号池，
 * 而看板上看到的是「几万个正常患者各抢了一次」。
 *
 * <p>所以身份改为从 HMAC 签名令牌里解出（见 {@link PatientIdentity}）。
 * 热路径上多出来的开销是一次 HMAC 计算 —— 纯 CPU、零 IO、微秒级，
 * 和它挡住的东西相比不值一提。
 *
 * <h2>压测怎么办</h2>
 *
 * 压测端拿着同一个密钥自己签令牌（{@code PATIENT_TOKEN_SECRET} 环境变量），
 * 预生成一批令牌轮着用 —— 真实压测本来就是这么做的（预认证令牌池）。
 * <b>刻意没有开「压测模式跳过校验」的后门</b>：那种开关最终一定会在某个环境里
 * 忘记关掉，而它长得就像一个正常配置项。
 */
@RestController
public class SeckillController {

    private final SeckillService seckillService;
    private final LocalSegmentManager segments;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final InstanceIdentity identity;
    private final PatientIdentity patients;

    public SeckillController(SeckillService seckillService, LocalSegmentManager segments,
                             StockRedisRepository stockRedis, HotConfigService hotConfig,
                             InstanceIdentity identity, PatientIdentity patients) {
        this.seckillService = seckillService;
        this.segments = segments;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.identity = identity;
        this.patients = patients;
    }

    @PostMapping("/seckill/{poolId}")
    public ResponseEntity<Map<String, Object>> seckill(@PathVariable long poolId,
                                                       @RequestParam(required = false) String deviceId,
                                                       HttpServletRequest request) {
        // holderId 来自服务端签发的令牌，不可伪造；deviceId 仍是**客户端自报**的。
        // 这是一条要诚实承认的信任边界：设备维度的风控判据（一机多号）以它为计数键，
        // 攻击者每次换一个随机 deviceId 就能让设备频次恒为 1 —— 对 holderId 成立的
        // 那套批评原样适用于它。根治要把设备指纹绑进 identify 签发的令牌，
        // 动协议和所有压测端；在那之前，设备判据只对诚实客户端有效（见 README 已知缺陷）。
        long holderId = patients.require(request);
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
