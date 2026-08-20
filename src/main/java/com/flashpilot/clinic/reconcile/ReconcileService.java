package com.flashpilot.clinic.reconcile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.flashpilot.clinic.reconcile.mapper.ReconcileMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.verify.ConsistencyChecker;

/**
 * 对账补偿：把校验器<b>发现</b>的号源残差真正<b>修回去</b>。
 *
 * <p>在这之前，一致性这条线是断的：五条等式能算出「号源凭空消失 37 个」、
 * 能说出故障模式的名字，然后……就没有然后了，人得自己去改库。
 * <b>一个只报警不处置的校验器，在无人值守的时段等于不存在。</b>
 *
 * <h2>难点全部在「什么时候不该动手」</h2>
 *
 * 补偿动作本身只有一行（把差额加回桶）。真正需要想清楚的是四道闸门，
 * 每一道都对应一种「补偿自己造成事故」的方式：
 *
 * <ol>
 *   <li><b>方向不对称：只自动补少卖，绝不自动补超卖。</b>
 *       少卖是号源卡住了，加回去最坏是把一个本来就存在的号重新放出来；
 *       超卖意味着<b>已经有患者拿到了号</b>，自动「回收」就是取消一个真实预约——
 *       那必须人工。这和 {@code AppointmentService} 的「让失败落在少卖那一侧」
 *       是同一条原则的延伸：<b>可逆的方向可以自动化，不可逆的方向必须留给人。</b></li>
 *
 *   <li><b>采样必须稳定。</b>残差有两种来源：真的卡住了，或者只是在一个正在变化的
 *       系统上做了非原子采样（归还是「先改 MySQL 状态、再还 Redis 号源」两步，
 *       在途期间必然差几个）。对采样偏移做补偿等于<b>凭空造号</b>，那是自己制造超卖。
 *       所以 {@code stableSample == false} 时直接不动，<b>而且不计入连续次数</b>——
 *       这一轮的读数没有意义，计进去会污染判据。</li>
 *
 *   <li><b>必须连续多次观测到同一个残差。</b>{@code stableSample} 只能排除
 *       「归还在途」这一种瞬态，还有消费在途、租约即将被回收等等。
 *       瞬态残差下一次探测就会自己消失，只有稳定复现的才是真卡住了。
 *       注意判据是「连续看到<b>同一个数</b>」而不是「连续都非零」——
 *       数字还在变说明系统还在动，此时补偿会补到一个中间态上。</li>
 *
 *   <li><b>单次补偿有上限。</b>残差大到离谱（比如接近整个号池）时，
 *       更可能是校验器自己算错了，而不是真丢了那么多号。这种情况下补偿会把错误
 *       放大成超卖，所以超过上限只告警不动手。
 *       <b>「我的判断可能是错的」必须编码进自动化里。</b></li>
 * </ol>
 *
 * <h2>为什么开关刻意不放进热配置白名单</h2>
 *
 * 控制面的 8 个热参数都可以被 L1 Agent 提案修改，<b>对账补偿的开关不在其中。</b>
 * 它不是「调大调小看效果」的性能旋钮，而是会<b>直接改动号源账目</b>的安全开关。
 * 让模型能打开它，等于让它获得凭空造号的能力——即使有护栏，
 * 这个能力本身就不该出现在提案空间里。
 * <b>护栏管的是「改到什么值」，白名单管的是「能不能碰」，后者是更强的边界。</b>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 保留最近几次对账动作，看板用。留档在表里，这个是免查库的快速视图。 */
    private static final int RECENT_KEEP = 20;

    /** 结论没变时的重播间隔：持续告警每 10 分钟留一条，既不刷表也不彻底消失。 */
    private static final long RELOG_WINDOW_MS = 10 * 60 * 1000L;

    private final ConsistencyChecker checker;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final FlashPilotProperties props;
    private final ReconcileMapper reconcileMapper;
    private final com.flashpilot.clinic.domain.ScheduleRepository schedules;

    /** 上一次观测到的残差，用于判断「连续多次是同一个数」。 */
    /**
     * 每个号池一份状态。
     *
     * <p>原来是两个裸字段，因为当时对账只看一个号池（实验号池）。改成遍历所有排班之后
     * 必须按池分开：判据是「<b>连续几次</b>观测到同一残差」，
     * 多个池共用一份计数器会让 A 池的观测把 B 池的连续性打断 ——
     * 结果是<b>每个池都永远攒不到 3 次，对账永远不动手</b>，
     * 而日志看起来一切正常（每轮都在「继续观察」）。
     */
    private record PoolState(int lastVanished, int consecutiveSame,
                             String lastPersistedDecision, long lastPersistedAtMs) {
        static PoolState fresh() {
            return new PoolState(0, 0, null, 0L);
        }
    }

    private final Map<Long, PoolState> states = new java.util.concurrent.ConcurrentHashMap<>();

    /** 轮转扫描的游标。每轮只扫一部分池，靠它保证所有池最终都被覆盖。 */
    private int sweepCursor;

    /** 上一条<b>已落库</b>的结论与时刻，用于自动路径的去重。见 {@link #shouldPersist}。 */


    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public ReconcileService(ConsistencyChecker checker, StockRedisRepository stockRedis,
                            HotConfigService hotConfig, FlashPilotProperties props, ReconcileMapper reconcileMapper,
                            com.flashpilot.clinic.domain.ScheduleRepository schedules) {
        this.checker = checker;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.props = props;
        this.reconcileMapper = reconcileMapper;
        this.schedules = schedules;
    }

    /** 一次对账的结论。只有 {@code acted} 为真才表示号源真的被改动了。 */
    public record Outcome(
            boolean acted,
            int vanished,
            int compensated,
            int consecutiveSame,
            String decision
    ) {
    }

    /**
     * 每轮最多对账几个号池。
     *
     * <p>需要上限是因为每个池的校验都要扫 32 个 Redis 桶加几次 MySQL 查询，
     * 而排班数量随业务增长没有上界。没有上限的话，某天排班多到一定程度，
     * 这个 30 秒一轮的旁路任务会开始和热路径抢 Redis ——
     * 而它是<b>旁路</b>任务，抢热路径的资源是绝对不该发生的事。
     *
     * <p>配合 {@link #sweepCursor} 轮转：这一轮扫不到的，下一轮接着扫。
     */
    private static final int SWEEP_PER_ROUND = 12;

    /**
     * 关闭状态下每隔几轮做一次 dry-run 观察。
     *
     * <p>配置注释里写着「关着的时候日志能看到它会做什么，确认判断正确了再打开」——
     * <b>而原来的实现是 {@code if (!enabled) return;}，关着时什么都不做、日志里一行都没有。</b>
     * 那句承诺是假的：想看只能手工去点 dry-run 接口，而一次点击看不出趋势。
     *
     * <p>「先观察再打开」这个理念是对的，所以补上实现而不是删掉那句注释：
     * 关着时也定期算账，但只算、不动手、只在<b>会动手或会拒绝</b>时记一行日志。
     * 这样运维打开开关之前，手上有几天的真实数据。
     *
     * <p>取 10 轮（默认 30 秒一轮 = 5 分钟一次）：观察不需要实时，
     * 而每 30 秒查一遍所有排班的账目对一个默认关闭的功能来说是白费的开销。
     */
    private static final int OBSERVE_EVERY_N_ROUNDS = 10;

    private int roundsSinceObserve;

    @Scheduled(fixedDelayString = "${flashpilot.clinic.reconcile.interval-ms:30000}")
    public void scheduled() {
        boolean on = props.clinic().reconcile().enabled();
        if (!on) {
            // 关闭状态：定期 dry-run，把「如果开着会做什么」写进日志。
            if (++roundsSinceObserve < OBSERVE_EVERY_N_ROUNDS) {
                return;
            }
            roundsSinceObserve = 0;
            try {
                observe();
            } catch (Exception e) {
                log.debug("[对账-观察] 本轮失败：{}", e.toString());
            }
            return;
        }
        try {
            sweep();
        } catch (Exception e) {
            // 对账是旁路任务，失败绝不能影响任何业务路径
            log.warn("[对账] 本轮失败，下个周期重试：{}", e.toString());
        }
    }

    /**
     * 只看不动手，且只在「有事」时出声。
     *
     * <p>不复用 {@code sweep()} 加个 dryRun 参数，是因为这两件事的<b>日志策略相反</b>：
     * 开着的时候每个动作都该留痕，关着的时候绝大多数轮次都是「账目平衡」，
     * 每 5 分钟记一行「一切正常」会把日志冲成噪声，而真正该看见的那几行反而被埋掉。
     */
    private void observe() {
        List<Long> pools = schedules.poolsNeedingReconcile();
        if (pools.isEmpty()) {
            return;
        }
        int n = Math.min(SWEEP_PER_ROUND, pools.size());
        List<Outcome> noteworthy = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            long poolId = pools.get((sweepCursor + i) % pools.size());
            try {
                Outcome o = runFor(poolId, true, false);
                if (o.vanished() != 0 || o.decision().contains("拒绝") || o.decision().contains("补偿")) {
                    noteworthy.add(o);
                }
            } catch (Exception e) {
                log.debug("[对账-观察] poolId={} 失败：{}", poolId, e.toString());
            }
        }
        sweepCursor = (sweepCursor + n) % pools.size();
        if (!noteworthy.isEmpty()) {
            Outcome sum = summarize(n, noteworthy);
            log.warn("[对账-观察] 对账当前是关闭的。如果打开，本轮会：{}（想立刻看细节：POST /admin/reconcile/run?dryRun=true）",
                    sum.decision());
        }
    }

    /**
     * 轮转对账所有需要对账的号池。
     *
     * <p><b>原来这里只对账一个池</b>：{@code checker.probe()} 读的是实验号池
     * （默认 1001、只在压测 preheat 时改写）。于是对账 —— 号源安全的最后一道防线 ——
     * 只保护最后一次压测用的那个池子，<b>真实排班的账目从来没被对过</b>。
     * 而运营看到看板上「对账已执行」，会以为账目查过了。
     *
     * <p>顺手清掉已经不需要对账的池的状态，否则每天的排班都会在 map 里留一份，
     * 进程跑几个月就是几万条无用状态。
     */
    public synchronized void sweep() {
        List<Long> pools = schedules.poolsNeedingReconcile();
        if (pools.isEmpty()) {
            states.clear();
            return;
        }
        states.keySet().retainAll(new java.util.HashSet<>(pools));

        int n = Math.min(SWEEP_PER_ROUND, pools.size());
        if (sweepCursor >= pools.size()) {
            sweepCursor = 0;
        }
        int acted = 0;
        for (int i = 0; i < n; i++) {
            long poolId = pools.get((sweepCursor + i) % pools.size());
            try {
                if (runFor(poolId, false, false).acted()) {
                    acted++;
                }
            } catch (Exception e) {
                // 单个池失败不能拖累其它池 —— 否则一个坏排班会让全部对账停摆
                log.warn("[对账] poolId={} 本轮失败，下个周期重试：{}", poolId, e.toString());
            }
        }
        sweepCursor = (sweepCursor + n) % pools.size();
        if (acted > 0) {
            log.warn("[对账] 本轮扫了 {} 个号池（共 {} 个），其中 {} 个动了账目", n, pools.size(), acted);
        }
    }

    /**
     * 跑一次对账。
     *
     * @param dryRun 只判断不动手。运维想确认「现在跑会做什么」时用，
     *               和控制面的 dry-run 同一个思路：<b>改账目的动作必须能先预演。</b>
     */
    public synchronized Outcome run(boolean dryRun) {
        // 人工触发对账<b>所有</b>需要对账的池，不只是实验池 ——
        // 运营点这个按钮的意思是「把账查一遍」，不是「查一下压测用的那个池子」。
        List<Long> pools = schedules.poolsNeedingReconcile();
        if (pools.isEmpty()) {
            return new Outcome(false, 0, 0, 0, "没有需要对账的号池（没有已放号且未过期的排班）");
        }
        List<Outcome> all = new java.util.ArrayList<>(pools.size());
        for (long poolId : pools) {
            try {
                all.add(runFor(poolId, dryRun, true));
            } catch (Exception e) {
                log.warn("[对账] poolId={} 失败：{}", poolId, e.toString());
            }
        }
        return summarize(pools.size(), all);
    }

    /**
     * 把多个池的结论汇总成一条，给接口和看板用。
     *
     * <p>汇总必须<b>先报坏消息</b>：有池被拒绝处置时，那件事比「另外 30 个池账目平衡」重要得多。
     * 按「账目平衡」汇总会把一个需要人介入的超卖告警埋在一句「一切正常」里。
     */
    static Outcome summarize(int poolCount, List<Outcome> all) {
        int compensated = 0;
        int actedCount = 0;
        Outcome worst = null;
        for (Outcome o : all) {
            compensated += o.compensated();
            if (o.acted()) {
                actedCount++;
            }
            // 只有「值得说」的池才参与竞选。
            //
            // 第一版写的是 `worst == null || ...`，于是第一个池<b>总会</b>被选中，
            // 「全部账目平衡」这条分支永远走不到 —— 32 个池全平衡时，
            // 汇总会说「最需要关注的：账目平衡」，读起来像是在报告一件事，其实什么都没发生。
            // 这个错是单元测试抓到的，不是我读代码看出来的。
            boolean noteworthy = o.acted() || o.vanished() != 0 || o.decision().contains("拒绝");
            if (!noteworthy) {
                continue;
            }
            worst = pickWorse(worst, o);
        }
        String head = "扫了 " + poolCount + " 个号池";
        if (actedCount > 0) {
            head += "，" + actedCount + " 个动了账目（共补回 " + compensated + " 个号源）";
        }
        String detail = worst == null ? "全部账目平衡" : "最需要关注的：" + worst.decision();
        return new Outcome(actedCount > 0, worst == null ? 0 : worst.vanished(),
                compensated, worst == null ? 0 : worst.consecutiveSame(),
                head + "；" + detail);
    }

    /**
     * 两个结论里哪个更该被人看到。
     *
     * <p>排序不看残差大小，先看<b>要不要人介入</b>：一个「拒绝自动处置」的小残差
     * 比一个已经自动补好的大残差重要得多 —— 前者在等人，后者已经结束了。
     * 同为拒绝（或同为非拒绝）时才比残差绝对值。
     */
    private static Outcome pickWorse(Outcome cur, Outcome candidate) {
        if (cur == null) {
            return candidate;
        }
        boolean curRefused = cur.decision().contains("拒绝");
        boolean candRefused = candidate.decision().contains("拒绝");
        if (candRefused != curRefused) {
            return candRefused ? candidate : cur;
        }
        return Math.abs(candidate.vanished()) > Math.abs(cur.vanished()) ? candidate : cur;
    }

    /**
     * 对账<b>一个</b>号池。
     *
     * @param manual 是否人工触发。只影响<b>留档策略</b>：人工的每一次都记
     *               （那是一次人的操作，本身就该可追溯），自动的连续相同结论要去重。
     */
    private Outcome runFor(long poolId, boolean dryRun, boolean manual) {
        ConsistencyChecker.Report r = checker.probe(poolId);
        PoolState st = states.getOrDefault(poolId, PoolState.fresh());

        // 判据是纯函数，见 ReconcileDecider —— 四道闸门及其交互全部可单测。
        ReconcileDecider.Decision d = ReconcileDecider.decide(
                r.vanished(), r.stableSample(), st.lastVanished(), st.consecutiveSame(),
                props.clinic().reconcile().consecutiveThreshold(),
                props.clinic().reconcile().maxCompensatePerRun(),
                dryRun);

        // 状态推进统一由判据决定，Service 不自己算 —— 否则两处逻辑迟早不一致。
        st = new PoolState(d.nextLastVanished(), d.nextConsecutive(),
                st.lastPersistedDecision(), st.lastPersistedAtMs());
        states.put(poolId, st);

        switch (d.action()) {
            case REFUSE_OVERSOLD -> log.error("[对账] {} poolId={}", d.reason(), r.poolId());
            case REFUSE_TOO_LARGE -> log.error("[对账] {} poolId={}", d.reason(), r.poolId());
            default -> { }
        }

        if (!d.acts()) {
            return record(poolId, r, false, d.amount(), d.reason(), manual);
        }

        // 真正动手。补偿必须落在活跃桶范围内 —— 和 bug ⑤ 同一个约定：
        // 落到非活跃桶的号，直连请求永远命不中，等于补了个假。
        int done = stockRedis.releaseSlots(r.poolId(), d.amount());
        // 桶范围由 StockRedisRepository 统一按活跃桶数执行，这里不再关心
        log.warn("[对账] 已补偿 poolId={} 残差={} 实际补回={}", r.poolId(), r.vanished(), done);

        // 补完立刻复验。不复验的话，补偿动作本身有 bug 时会每 30 秒重复补一次 ——
        // 那就从少卖变成超卖了，而且是自动化在持续制造超卖。
        ConsistencyChecker.Report after = checker.probe(poolId);
        String verify = after.stableSample()
                ? (after.vanished() == 0 ? "复验通过，残差已归零"
                        : "复验仍有残差 " + after.vanished() + "，需人工核查")
                : "复验时采样不稳定，下轮再确认";

        return record(poolId, r, true, done, "已补偿 " + done + " 个号源到活跃桶；" + verify, manual);
    }

    private Outcome record(long poolId, ConsistencyChecker.Report r, boolean acted, int compensated,
                           String decision, boolean manual) {
        PoolState st = states.getOrDefault(poolId, PoolState.fresh());
        Outcome o = new Outcome(acted, r.vanished(), compensated, st.consecutiveSame(), decision);
        if (shouldPersist(acted, decision, manual, st)) {
            persist(r, acted, compensated, decision);
            states.put(poolId, new PoolState(st.lastVanished(), st.consecutiveSame(),
                    decision, System.currentTimeMillis()));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("poolId", r.poolId());
        row.put("vanished", r.vanished());
        row.put("acted", acted);
        row.put("compensated", compensated);
        row.put("decision", decision);
        synchronized (recent) {
            recent.addFirst(row);
            while (recent.size() > RECENT_KEEP) {
                recent.removeLast();
            }
        }
        return o;
    }

    /**
     * 该不该留档。
     *
     * <p>基本规则：只有「真动手」「拒绝动手」「dry-run 预演」值得留档 ——
     * 「账目平衡」「继续观察」每 30 秒一条会把表冲成噪声，
     * 和 {@code probe()}/{@code check()} 分开是同一个理由。
     *
     * <p><b>但光这样还不够，我第一版就漏了：拒绝类结论天生是持续性的。</b>
     * 超卖告警不会自己好（那正是它需要人介入的原因），于是每个周期都会写一条
     * 一模一样的「拒绝自动处置」——默认 30 秒间隔下一天 2880 行。
     * 实测把间隔调到 5 秒跑几分钟，留档里就出现了成对的重复记录。
     * <b>我做去重就是为了避免噪声，却恰好把最会重复的那一类放了过去。</b>
     *
     * <p>所以自动路径要对<b>连续相同的结论</b>去重，同时保留一个重播窗口：
     * 结论没变也每隔 {@code RELOG_WINDOW_MS} 记一次，
     * 这样「它还在拒绝」不会从留档里彻底消失 —— 完全不记和记 2880 条一样糟。
     *
     * <p>人工触发的一律记：那是一次人的操作，本身就该可追溯，
     * 而人不会每 30 秒点一次。
     */
    private boolean shouldPersist(boolean acted, String decision, boolean manual, PoolState st) {
        boolean worth = acted || decision.contains("拒绝") || decision.contains("dry-run");
        if (!worth) {
            return false;
        }
        if (manual || acted) {
            return true;      // 人的操作、以及真的改了账目，一律留档
        }
        boolean same = decision.equals(st.lastPersistedDecision());
        boolean stale = System.currentTimeMillis() - st.lastPersistedAtMs() > RELOG_WINDOW_MS;
        return !same || stale;
    }

    private void persist(ConsistencyChecker.Report r, boolean acted, int compensated, String decision) {
        try {
            reconcileMapper.insertLog(r.poolId(), r.initialStock(), r.bucketSum(), r.leaseHeld(),
                    r.orderCount(), r.vanished(), acted, compensated, truncate(decision, 500));
        } catch (Exception e) {
            // 留档失败不能让对账本身失败
            log.warn("[对账] 留档失败：{}", e.toString());
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    /** 最近的对账动作，看板用。 */
    public List<Map<String, Object>> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public List<Map<String, Object>> history(int limit) {
        return reconcileMapper.history(limit);
    }

    public boolean enabled() {
        return props.clinic().reconcile().enabled();
    }

    /**
     * 各号池里连续观测次数最多的那个值，看板用。
     *
     * <p>取最大而不是求和或平均：这个数的用途是回答「还差几次就会动手」，
     * 而运营关心的是<b>最接近动手</b>的那个池。
     */
    public int consecutiveSame() {
        int max = 0;
        for (PoolState st : states.values()) {
            max = Math.max(max, st.consecutiveSame());
        }
        return max;
    }

    /** 当前被跟踪的号池数，看板用 —— 它是「对账到底覆盖了多少个排班」的直接答案。 */
    public int trackedPools() {
        return states.size();
    }
}
