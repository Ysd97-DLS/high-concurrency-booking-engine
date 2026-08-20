package com.flashpilot.clinic.admin;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.flashpilot.clinic.admin.mapper.AdminMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.clinic.risk.RiskControlService;
import com.flashpilot.clinic.reconcile.ReconcileService;
import com.flashpilot.config.InstanceRegistry;
import com.flashpilot.clinic.risk.SlowLane;
import com.flashpilot.controlplane.MetricsCollector;
import com.flashpilot.controlplane.MetricsSnapshot;
import com.flashpilot.controlplane.config.ConfigAuditRepository;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.StockRedisRepository;

/**
 * 运营端接口。
 *
 * <p>完成判据是「<b>运营能不碰代码完成一次放号</b>」：建排班 → 配置放号节奏 → 开始放号 →
 * 看实时看板 → 必要时手动干预或停止。这一组接口就是为了让这条路径走得通。
 *
 * <p>和患者端的分工：患者端是业务旅程，这里是<b>运营视角</b>——
 * 关心的是号池状态、成交分布、风控命中、控制面在做什么，
 * 以及出问题时能不能手动接管。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminMapper adminMapper;
    private final ReleaseService release;
    private final StockRedisRepository stockRedis;
    private final MetricsCollector collector;
    private final HotConfigService hotConfig;
    private final ConfigAuditRepository audit;
    private final RiskControlService riskControl;
    private final SlowLane slowLane;
    private final ReconcileService reconcile;
    private final InstanceRegistry instances;
    private final com.flashpilot.clinic.AppointmentService appointments;

    public AdminController(AdminMapper adminMapper, ReleaseService release, StockRedisRepository stockRedis,
                           MetricsCollector collector, HotConfigService hotConfig,
                           ConfigAuditRepository audit, RiskControlService riskControl, SlowLane slowLane,
                           ReconcileService reconcile, InstanceRegistry instances,
                           com.flashpilot.clinic.AppointmentService appointments) {
        this.appointments = appointments;
        this.reconcile = reconcile;
        this.instances = instances;
        this.adminMapper = adminMapper;
        this.release = release;
        this.stockRedis = stockRedis;
        this.collector = collector;
        this.hotConfig = hotConfig;
        this.audit = audit;
        this.riskControl = riskControl;
        this.slowLane = slowLane;
    }

    // ---------- 排班管理 ----------

    /** 排班列表，带放号进度。运营的主界面。 */
    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules(@RequestParam(required = false) String date) {
        return adminMapper.listSchedules(date == null ? null : LocalDate.parse(date));
    }

    /** 建排班。运营录入的入口。 */
    @PostMapping("/schedules")
    public Map<String, Object> createSchedule(
            @RequestParam long doctorId,
            @RequestParam String visitDate,
            @RequestParam(defaultValue = "AM") String period,
            @RequestParam(defaultValue = "NORMAL") String slotType,
            @RequestParam int totalSlots,
            @RequestParam int feeCents,
            @RequestParam(defaultValue = "08:00:00") String visitStart,
            @RequestParam(defaultValue = "11:30:00") String visitEnd) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            // 必须把自增主键取回来。第一版只返回 {ok, message}，运营端建完排班拿不到 ID，
            // 后续所有操作（开放放号、查进度、停止）全都做不了 —— 实测调
            // POST /admin/schedules//open 直接 404。
            // 这类「写成功了但不告诉你写的是哪一条」的接口，是纯后端阶段最容易漏的缺陷，
            // 因为用 curl 手测单个接口时看不出问题，只有把流程串起来才会暴露。
            AdminMapper.NewSchedule ns = new AdminMapper.NewSchedule(
                    doctorId, LocalDate.parse(visitDate), period, slotType, feeCents, totalSlots,
                    java.time.LocalTime.parse(visitStart), java.time.LocalTime.parse(visitEnd));
            adminMapper.createSchedule(ns);
            Number id = ns.getId();
            r.put("ok", true);
            r.put("scheduleId", id == null ? null : id.longValue());
            r.put("totalSlots", totalSlots);
            r.put("message", "排班已创建，状态 PENDING。调 POST /admin/schedules/{id}/open 开始放号");
        } catch (Exception e) {
            r.put("ok", false);
            // 最常见的是撞 uk_doctor_slot：同一医生同一天同一时段同一号别重复建号池
            r.put("message", "创建失败（同一医生同天同时段同号别只能有一个排班）：" + e.getMessage());
        }
        return r;
    }

    // ---------- 放号 ----------

    /** 开始放号。节奏由控制面的 release.spreadSeconds 决定。 */
    @PostMapping("/schedules/{id}/open")
    public Map<String, Object> open(@PathVariable long id) {
        return release.open(id);
    }

    /** 停止放号，剩余号不再放出。出问题时的第一个手动干预手段。 */
    @PostMapping("/schedules/{id}/close")
    public Map<String, Object> close(@PathVariable long id) {
        return release.close(id);
    }

    @GetMapping("/schedules/{id}/progress")
    public Map<String, Object> progress(@PathVariable long id) {
        return release.progress(id);
    }

    // ---------- 实时看板 ----------

    /**
     * 运营看板：一次请求拿到全部关键状态。
     *
     * <p>刻意做成单接口聚合而不是让前端并发调七八个接口——
     * 看板的价值在于「各项指标是同一时刻的快照」。分开调的话，
     * 号池余量和成交数可能差了几百毫秒，运营看到的是一组互相矛盾的数字。
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "1001") long scheduleId) {
        Map<String, Object> m = new LinkedHashMap<>();

        // 号池实时状态（Redis）
        StockRedisRepository.Stats stats = stockRedis.stats(scheduleId);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("scheduleId", scheduleId);
        pool.put("bucketSum", stats.bucketSum());
        pool.put("leaseHeld", stats.leaseHeld());
        pool.put("instances", stats.instances());
        pool.put("globalRemaining", stats.total());
        m.put("pool", pool);

        // 放号进度（MySQL）
        m.put("release", release.progress(scheduleId));
        m.put("releasingSchedules", release.activePlanCount());

        // 预约状态分布 —— 运营最关心的一屏
        m.put("appointments", adminMapper.statusBreakdown(scheduleId));

        // 数据面与控制面指标
        MetricsSnapshot snap = collector.latest();
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("effectiveQps", Math.round(snap.effectiveQps()));
        perf.put("requestQps", Math.round(snap.requestQps()));
        perf.put("rejectRate", Math.round(snap.rejectRate() * 1000) / 1000.0);
        perf.put("p99WindowMs", Math.round(snap.p99Ms() * 100) / 100.0);
        perf.put("streamPending", snap.streamPending());
        perf.put("bucketSkew", Math.round(snap.bucketSkew() * 1000) / 1000.0);
        perf.put("segmentHitRatio", Math.round(snap.segmentHitRatio() * 10000) / 10000.0);
        m.put("performance", perf);

        // 风控
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("demoted", riskControl.demotedCount());
        risk.put("blocked", riskControl.blockedCount());
        risk.put("blocklistSize", riskControl.blocklistSize());
        risk.put("slowLanePassed", slowLane.passedCount());
        risk.put("slowLaneDropped", slowLane.droppedCount());
        risk.put("cmsMemoryKb", riskControl.cmsMemoryBytes() / 1024);
        // 噪声底和「判据是否可信」：CMS 的估计值天然含有约 事件数/width 个别人的计数，
        // 这个数接近阈值时频次判据就从「识别高频」退化成「命中所有人」，
        // 而这个退化在成交数和误拒率上都看不出来。必须让运维直接看到。
        risk.put("cmsNoiseFloor", riskControl.noiseFloor());
        // 实际生效阈值会在噪声高时被自动抬高。两个数一起给，运营才能看出
        // 「我配的 3 到底还算不算数」——只给配置值会让人以为自己调的参数生效了。
        risk.put("effectiveThreshold", riskControl.effectiveThresholdNow());
        risk.put("configuredThresholdInEffect", riskControl.configuredThresholdInEffect());
        m.put("risk", risk);

        // 对账补偿。运营要能看到「自动化有没有在动账目」——
        // 一个会自己改数据的任务，必须在看板上有一席之地，否则它就是个黑箱。
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("enabled", reconcile.enabled());
        rec.put("consecutiveSame", reconcile.consecutiveSame());
        // 覆盖了几个号池。这个数必须露出来 —— 对账原来只看实验号池（默认 1001），
        // 真实排班的账目从来没被对过，而看板上「对账已启用」让人以为查过了。
        // 一个只保护压测池的最后防线，比没有防线更危险，因为它给了虚假的安心。
        rec.put("trackedPools", reconcile.trackedPools());
        rec.put("recent", reconcile.recent());
        m.put("reconcile", rec);

        // 部署形态。这一块存在的理由很具体：dedupe.mode=LOCAL 要求网关按 userId 粘性路由，
        // 违反它会让号源静默蒸发（实测双实例下没了 20%），而客户端全部收到「成功」。
        // 启动日志里已经打了 ERROR，但日志是「出事后才有人翻」的东西；
        // 看板是「平时就在看」的东西，所以这个状态必须同时出现在两个地方。
        Map<String, Object> deploy = new LinkedHashMap<>();
        List<String> alive = instances.aliveInstances();
        deploy.put("instances", alive);
        deploy.put("instanceCount", alive.size());
        deploy.put("dedupeMode", instances.dedupeMode());
        deploy.put("dedupeUnsafe", instances.dedupeUnsafe());
        m.put("deploy", deploy);

        // 控制面当前参数 + 最近变更
        m.put("config", hotConfig.snapshot());
        m.put("configVersion", snap.configVersion());
        m.put("recentChanges", audit.recent(10));

        return m;
    }

    /** 风控命中明细，运营用来判断阈值调得合不合适。 */
    @GetMapping("/risk/events")
    public List<Map<String, Object>> riskEvents(@RequestParam(defaultValue = "50") int limit) {
        return adminMapper.riskEvents(limit);
    }

    // ---------- 对账补偿 ----------

    /**
     * 手工跑一次对账。
     *
     * <p>默认 {@code dryRun=true}：<b>改账目的动作必须能先预演。</b>
     * 和控制面改热参数的 dry-run 是同一个思路——运维需要先知道「如果现在跑会做什么」，
     * 再决定要不要真做。默认值选 true 而不是 false，是因为这个接口会改号源，
     * 手滑的代价不对称。
     */
    @PostMapping("/reconcile/run")
    public ReconcileService.Outcome reconcileRun(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return reconcile.run(dryRun);
    }

    /** 对账留档。只记「真动手 / 拒绝动手 / 预演」三类，不记每 30 秒一次的平衡探测。 */
    @GetMapping("/reconcile/history")
    public List<Map<String, Object>> reconcileHistory(@RequestParam(defaultValue = "30") int limit) {
        return reconcile.history(limit);
    }

    /** 失约黑名单，运营可以人工核查误判。 */
    @GetMapping("/patients/blocked")
    public List<Map<String, Object>> blockedPatients() {
        return adminMapper.blockedPatients();
    }

    /** 人工解除黑名单。误判必须有人工兜底通道，否则风控就是不可接受的。 */
    @PostMapping("/patients/{id}/unblock")
    public Map<String, Object> unblock(@PathVariable long id) {
        int n = adminMapper.unblock(id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", n == 1);
        r.put("message", n == 1 ? "已解除限制并清零失约次数（最长 30 秒后各实例的本地缓存刷新生效）"
                : "患者不存在");
        return r;
    }

    // ---------- 就诊结果登记（原来错放在患者端） ----------
    //
    // 这两个接口本来是 POST /clinic/appointments/{apptNo}/complete 和 /no-show，
    // 完全无校验。后果实测：
    //   · complete 把单子推进 COMPLETED 终态 —— 患者从此退不了号
    //   · no-show 调三次就让一个真实患者 no_show_count 到 3 → 禁约 30 天
    //
    // 挪到 /admin 之后由 AdminGuard 兜住。**注意它们不是「加所有权校验」能修的**：
    // 患者对自己的单也不该有标记就诊完成或失约的权限 —— 那是院方的判断。
    // 「谁有权做这件事」和「这是谁的数据」是两个不同的问题，
    // 只想着后者就会把院方操作留在患者端。

    /** 登记就诊完成。BOOKED → COMPLETED。 */
    @PostMapping("/appointments/{apptNo}/complete")
    public Map<String, Object> complete(@PathVariable String apptNo) {
        return operatorReply(appointments.complete(apptNo), apptNo);
    }

    /**
     * 登记失约。BOOKED → NO_SHOW，不归还号源。
     *
     * <p>日常由 {@code NoShowScanTask} 自动扫，这个接口是给院方手工纠正用的。
     */
    @PostMapping("/appointments/{apptNo}/no-show")
    public Map<String, Object> noShow(@PathVariable String apptNo) {
        return operatorReply(appointments.noShow(apptNo), apptNo);
    }

    private static Map<String, Object> operatorReply(
            com.flashpilot.clinic.AppointmentService.Result result, String apptNo) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", result == com.flashpilot.clinic.AppointmentService.Result.OK);
        r.put("result", result.name());
        r.put("apptNo", apptNo);
        r.put("message", result == com.flashpilot.clinic.AppointmentService.Result.OK
                ? "已登记" : "当前状态不允许该操作（单据不存在，或已不是「已预约」状态）");
        return r;
    }
}
