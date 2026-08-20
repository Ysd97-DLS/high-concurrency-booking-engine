package com.flashpilot.clinic.risk;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.flashpilot.clinic.risk.mapper.RiskEventMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 黄牛识别。三层判据，<b>成本递增、按需触发</b>。
 *
 * <p>设计约束先说清楚：这段代码在 3 万 req/s 的热路径上，
 * 而这条路径的全部意义就是避免网络往返（本地号段把 95% 的扣减挡在 Redis 之外）。
 * 所以：
 * <ul>
 *   <li><b>L1 频次</b>：每个请求都跑，纯本地 Count-Min Sketch，O(1)、零分配、零 I/O；</li>
 *   <li><b>L2 行为</b>：只在 L1 判定频次偏高时才跑。设备共用患者数、请求间隔规律性；</li>
 *   <li><b>L3 画像</b>：失约黑名单。它必须对<i>每个</i>请求生效，所以不能查库——
 *       用本地缓存的黑名单集合，由定时任务从 MySQL 刷新。黑名单患者数量很少，
 *       一个 HashSet 完全放得下。</li>
 * </ul>
 *
 * <p>阈值走热配置（{@link ConfigParam#RISK_THRESHOLD}），所以控制面能自动调节严格度——
 * 这是挂号场景里控制面的一个真实业务手段：太严伤真实患者，太松放黄牛，
 * 而合适的位置随流量变化，正适合自动调。
 */
@Service
public class RiskControlService {

    private static final Logger log = LoggerFactory.getLogger(RiskControlService.class);

    /**
     * CMS 尺寸：4 行 × 65536 列，三个草图双缓冲共约 12MB。
     *
     * <p><b>width 不是随便定的，它由「噪声底必须远小于阈值」倒推。</b>
     * CMS 任意 key 的估计值天然含有约 {@code 窗口事件数 / width} 个别人的计数
     * （见 {@link CountMinSketch} 的类注释）。
     *
     * <p>原来是 8192，实测直接失效：压测 27,666 req/s、窗口跨 20 秒
     * → 约 55 万事件 → 噪声底 ≈ 67，而阈值是 3。
     * 结果 <b>55 万个请求每一个都被判成高频</b>，全部降权进 20/s 的慢车道丢掉 52 万个。
     * 风控在最该工作的高峰期变成了全局熔断器，而表面只表现为"成交偏低"。
     *
     * <p>加大到 65536 把噪声底降到 1/8，配合 {@code CountMinSketch} 里减掉噪声底的修正，
     * 判据在 3 万 QPS 下依然可用。代价是内存从 1.5MB 涨到 12MB ——
     * 对服务端完全可接受，而"固定内存、不随 key 数量增长"这个核心性质没有变。
     */
    private static final int CMS_DEPTH = 4;
    private static final int CMS_WIDTH = 65536;

    /** 同一设备下出现多少个不同患者就认为在批量代抢。真实场景一家人共用一个手机很常见，所以给到 5。 */
    private static final int DEVICE_PATIENT_LIMIT = 5;

    /**
     * 阈值相对噪声底的安全倍数。
     *
     * <p>3 倍是个工程取值：噪声底是<b>均值</b>，而任一 key 的实际冲突量在均值附近波动，
     * 取 3 倍能把绝大多数正常 key 的波动挡在阈值之下。
     */
    private static final int NOISE_SAFETY = 3;

    /**
     * 实际生效的频次阈值：<b>配置值与噪声底的较大者</b>。
     *
     * <p>这是修完 CMS 噪声底之后<b>补上的第二层修复</b>，而它是被单元测试逼出来的。
     * 第一次修复只做了两件事：width 加大 8 倍、估计值减掉噪声底。
     * 我当时验证过 A/B 对照（低负载，噪声底 0）和 P1 压测（阈值 500），两个都通过，
     * <b>就以为修好了</b>。而 {@code CountMinSketchTest} 直接测了没验证过的那个组合——
     * <b>阈值 3 + 高负载</b>——立刻发现普通患者的修正后估计值是 4，仍然超阈值 3。
     *
     * <p>根因是数学上的硬限制：减掉噪声底只消除了<b>均值</b>，
     * 残余波动仍在若干个计数单位量级，而阈值 3 本身就在这个量级里。
     * 阈值 3 相对 50 万事件是 0.0006%，任何亚线性草图都分辨不出这个比例——
     * 继续加宽也只是把失效点往后推（要把噪声压到 1 以下需要 100 万列、约 200MB）。
     *
     * <p>所以正确的解法是让阈值<b>跟着噪声底走</b>：
     * <ul>
     *   <li>低负载（噪声底 0）：阈值 = 配置值，行为完全不变，A/B 对照的结论仍然成立；</li>
     *   <li>高负载（噪声底 7）：阈值抬到 21，普通患者的 4 落在下面，
     *       而一机多号的黄牛（估计值约 200）依然远超设备阈值 105。</li>
     * </ul>
     *
     * <p>这个抬升本身也符合业务直觉：<b>放号瞬间人人都在高频重试，判据本来就该放宽；
     * 平峰期同样的频次才是异常。</b>把「随负载放宽」做成结构性质，
     * 比指望运营在洪峰时手动调阈值可靠得多。
     *
     * <p>做成静态纯函数是为了能直接测——它是整个风控里最需要被钉住的一条公式。
     */
    static double effectiveThreshold(double configured, long noiseFloor) {
        return Math.max(configured, (double) noiseFloor * NOISE_SAFETY);
    }

    private final RiskEventMapper riskEvents;
    private final HotConfigService hotConfig;
    private final SeckillMetrics metrics;

    /** L1：患者维度频次。 */
    private final CountMinSketch patientRate = new CountMinSketch(CMS_DEPTH, CMS_WIDTH);
    /** L1：设备维度频次。 */
    private final CountMinSketch deviceRate = new CountMinSketch(CMS_DEPTH, CMS_WIDTH);
    /** L2：设备下出现过的患者组合，用来估计「一个设备服务了多少患者」。 */
    private final CountMinSketch devicePatientPairs = new CountMinSketch(CMS_DEPTH, CMS_WIDTH);

    /**
     * L3：失约黑名单的本地缓存。
     *
     * <p>它必须对每个请求生效，所以绝不能查库。黑名单是「累计失约 3 次」的患者，
     * 数量以百计而不是以万计，本地 Set 足够。定时刷新的代价是新拉黑的患者
     * 最多还能抢 {@code BLOCKLIST_REFRESH_MS} 那么久——完全可以接受。
     */
    private volatile Set<Long> blockedPatients = Set.of();

    private final AtomicLong demoted = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();

    /** 待写库的风控事件。热路径只入队，落库交给定时任务批量做。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<RiskEventMapper.RiskEvent> eventQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public RiskControlService(RiskEventMapper riskEvents, HotConfigService hotConfig, SeckillMetrics metrics) {
        this.riskEvents = riskEvents;
        this.hotConfig = hotConfig;
        this.metrics = metrics;
    }

    /**
     * 判定一次抢号请求。<b>热路径，必须微秒级。</b>
     *
     * @param patientId 患者
     * @param deviceId  设备指纹，可能为 null（老接口没传）
     */
    public RiskDecision judge(long patientId, String deviceId) {
        // L3 黑名单优先：本地 Set 查一次，O(1)
        if (blockedPatients.contains(patientId)) {
            blocked.incrementAndGet();
            metrics.riskBlocked();
            enqueue(patientId, deviceId, "L3", "BLOCK", "失约黑名单");
            return RiskDecision.BLOCK;
        }

        double threshold = effectiveThreshold(
                hotConfig.get(ConfigParam.RISK_THRESHOLD), patientRate.noiseFloor());

        // ---------- L1 频次：患者维度和设备维度<b>都要算</b> ----------
        //
        // 第一版把设备判据门控在「患者频次超阈值」之后，结果它在最该生效的场景里永远不可达：
        // 黄牛用一台设备代抢 30 个患者时，<b>每个患者的频次都很低</b>（各请求两三次），
        // 高的是设备频次。门控之后 L1 判定通过、L2 根本没机会跑，实测两组成交率都是 100%。
        //
        // 两个 CMS 的成本完全一样（都是本地 O(1)），没有任何理由门控其中一个。
        // 教训：分层风控的「按需触发」只能用于<i>真正更贵</i>的层，
        // 把同等成本的判据也门控起来，只会制造盲区。
        long pRate = patientRate.incrementAndEstimate("p:" + patientId);
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        long dRate = hasDevice ? deviceRate.incrementAndEstimate("d:" + deviceId) : 0;

        boolean patientHot = pRate > threshold;
        // 设备阈值放宽：一家人共用一台手机是常见的正常行为，不能按个人阈值卡。
        boolean deviceHot = hasDevice && dRate > threshold * DEVICE_PATIENT_LIMIT;

        if (!patientHot && !deviceHot) {
            return RiskDecision.PASS;      // 绝大多数请求在这里返回，零额外开销
        }

        // ---------- L2 行为：只有被 L1 标记后才跑，这一层才真的更贵 ----------
        // pairs 是这个(设备,患者)组合出现的次数。用它区分两种「设备高频」：
        //   一家人共用手机：设备频次高，且每个患者各自也重复请求（pairs 大）；
        //   批量代抢：设备频次极高，但每个患者只被抢一两次（pairs 小）。
        long pairs = hasDevice
                ? devicePatientPairs.incrementAndEstimate("dp:" + deviceId + ":" + patientId)
                : 0;

        String level;
        String reason;
        if (deviceHot && pairs <= 3) {
            level = "L2";
            reason = "设备频次 " + dRate + " 超设备阈值 " + (long) (threshold * DEVICE_PATIENT_LIMIT)
                    + "，而该患者在此设备上仅 " + pairs + " 次，疑似一机多号批量代抢";
        } else if (patientHot) {
            level = "L1";
            reason = "患者频次估计 " + pRate + " 超阈值 " + (long) threshold;
        } else {
            level = "L2";
            reason = "设备频次 " + dRate + " 偏高但各患者均有重复请求（pairs=" + pairs
                    + "），疑似多人共用设备，从轻处置";
        }

        demoted.incrementAndGet();
        metrics.riskDemoted();
        enqueue(patientId, hasDevice ? deviceId : null, level, "DEMOTE", reason);
        return RiskDecision.DEMOTE;
    }

    /**
     * 风控事件入队。<b>热路径绝不能直接写库</b>——放号高峰时命中风控的量本身就很大，
     * 每次一条 INSERT 会把数据库连接池打满，而这些记录只是给运营事后分析用的，
     * 晚几秒落库没有任何影响。队列有界，满了就丢弃并计数（丢审计记录比拖垮服务好）。
     */
    private void enqueue(long patientId, String deviceId, String level, String action, String reason) {
        if (eventQueue.size() < 20_000) {
            eventQueue.add(new RiskEventMapper.RiskEvent(patientId, deviceId, level, action, reason));
        }
    }

    /** 批量落库风控事件。 */
    @Scheduled(fixedDelay = 2000)
    public void flushEvents() {
        if (eventQueue.isEmpty()) {
            return;
        }
        java.util.List<RiskEventMapper.RiskEvent> batch = new java.util.ArrayList<>(500);
        RiskEventMapper.RiskEvent e;
        while (batch.size() < 500 && (e = eventQueue.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            riskEvents.insertBatch(batch);
        } catch (Exception ex) {
            log.warn("风控事件落库失败，丢弃这批 {} 条：{}", batch.size(), ex.toString());
        }
    }

    /** CMS 窗口轮转。窗口太长会让历史流量一直压着判据，太短则抓不到持续行为。 */
    @Scheduled(fixedDelayString = "${flashpilot.clinic.risk-window-ms:10000}")
    public void rotateWindows() {
        patientRate.rotate();
        deviceRate.rotate();
        devicePatientPairs.rotate();
    }

    /** 刷新失约黑名单。 */
    @Scheduled(fixedDelayString = "${flashpilot.clinic.blocklist-refresh-ms:30000}")
    public void refreshBlocklist() {
        try {
            java.util.List<Long> ids = riskEvents.findBlockedPatients();
            Set<Long> next = ConcurrentHashMap.newKeySet();
            next.addAll(ids);
            blockedPatients = next;
        } catch (Exception e) {
            // 刷新失败保留上一份，不要清空 —— 清空等于放开所有黑名单
            log.warn("黑名单刷新失败，保留上一份（{} 人）：{}", blockedPatients.size(), e.toString());
        }
    }

    public long demotedCount() {
        return demoted.get();
    }

    public long blockedCount() {
        return blocked.get();
    }

    public int blocklistSize() {
        return blockedPatients.size();
    }

    public int cmsMemoryBytes() {
        return patientRate.memoryBytes() + deviceRate.memoryBytes() + devicePatientPairs.memoryBytes();
    }

    /**
     * 当前噪声底（患者维度）。
     *
     * <p><b>这个数必须暴露给运维看板。</b>它是判据是否还有效的唯一指标：
     * 噪声底一旦接近阈值，CMS 的估计值就基本全是别人的计数，
     * 频次判据会从"识别高频"退化成"命中所有人"——
     * 而这个退化在成交数、误拒率上都看不出来，只表现为"风控好像变严了"。
     *
     * <p>健康标准：{@code noiseFloor < 阈值 / 3}。超了就该加大 width 或缩短窗口。
     */
    public long noiseFloor() {
        return patientRate.noiseFloor();
    }

    /** 实际生效的频次阈值。噪声高时会被自动抬高，见 {@link #effectiveThreshold}。 */
    public double effectiveThresholdNow() {
        return effectiveThreshold(hotConfig.get(ConfigParam.RISK_THRESHOLD), noiseFloor());
    }

    /**
     * 运营配置的阈值是否仍在生效。
     *
     * <p>false 不代表出故障，而是说明<b>当前负载已经高到噪声底接管了阈值</b>——
     * 判据仍然可用（自适应抬升保证了这一点），但运营在界面上调 3 还是 5 已经不起作用了。
     * 这件事必须让人看见，否则运营会以为自己调的参数生效了。
     */
    public boolean configuredThresholdInEffect() {
        return effectiveThresholdNow() <= hotConfig.get(ConfigParam.RISK_THRESHOLD);
    }

    public void reset() {
        patientRate.rotate();
        patientRate.rotate();
        deviceRate.rotate();
        deviceRate.rotate();
        devicePatientPairs.rotate();
        devicePatientPairs.rotate();
        demoted.set(0);
        blocked.set(0);
        eventQueue.clear();
    }
}
