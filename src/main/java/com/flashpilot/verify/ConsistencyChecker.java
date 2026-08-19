package com.flashpilot.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flashpilot.clinic.domain.ApptPersistRepository;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.dataplane.stream.ConsumerStats;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 双向一致性校验器 —— 把「我保证不超卖」从一句空话变成<b>可执行的断言</b>。
 *
 * <p>四组等式：
 * <pre>
 *   ① 不超卖    MySQL 订单数 ≤ 初始库存
 *   ② 不少卖    售罄时 MySQL 订单数 == 初始库存
 *   ③ 库存守恒  初始库存 == Σ桶剩余 + Σ实例本地持有 + 已发出的成交事件数
 *   ④ 链路守恒  已发出事件数 == 已入库 + 重复跳过 + 超卖拦截 + 死信 + 未处理
 * </pre>
 *
 * <p>其中<b>等式 ③ 最有价值</b>：它把「库存现在到底在谁手里」变得可观测。
 * 少卖的根因（库存卡在宕机实例的本地余量里）会被这条等式直接暴露成一个非零残差，
 * 而只检查「有没有超卖」的做法永远发现不了它。
 */
@Service
public class ConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyChecker.class);

    private final StockRedisRepository stockRedis;
    private final ApptPersistRepository orders;
    private final ConsumerStats consumerStats;
    private final ExperimentContext experiment;
    private final SeckillMetrics metrics;
    private final JdbcTemplate jdbc;

    public ConsistencyChecker(StockRedisRepository stockRedis, ApptPersistRepository orders,
                              ConsumerStats consumerStats, ExperimentContext experiment,
                              SeckillMetrics metrics, JdbcTemplate jdbc) {
        this.stockRedis = stockRedis;
        this.orders = orders;
        this.consumerStats = consumerStats;
        this.experiment = experiment;
        this.metrics = metrics;
        this.jdbc = jdbc;
    }

    /**
     * 校验报告。
     *
     * @param vanished 库存守恒的残差。>0 表示<b>库存凭空消失（少卖）</b>，
     *                 &lt;0 表示发出的成交事件比库存还多（潜在超卖）
     */
    public record Report(
            long poolId,
            int initialStock,
            int bucketSum,
            int leaseHeld,
            long streamLength,
            int orderCount,
            int soldStock,
            long consumed,
            long duplicate,
            long deadLetter,
            long oversoldBlocked,
            int oversold,
            int undersold,
            int vanished,
            long unprocessed,
            boolean soldOut,
            boolean passed,
            /**
             * 采样期间系统是否稳定（没有号源正在归还）。
             *
             * <p>对账补偿<b>必须</b>看这个字段：残差有两种来源 ——
             * 真的有号源卡住了，或者只是在一个正在变化的系统上做了非原子采样。
             * 对后者做补偿等于凭空造号，那是自己制造超卖。
             */
            boolean stableSample,
            List<String> equations
    ) {
    }

    /**
     * 只算不记：返回校验结果但<b>不落库、不打日志</b>。
     *
     * <p>给对账补偿任务用的。它每 30 秒探一次，如果每次都往 {@code t_verify_report}
     * 写一行，一天就是 2880 行噪声，真正需要留档的那几次反而被埋掉了。
     * 而且对账的判据是「连续几次观测到同一残差」，中间那几次探测本身没有留档价值。
     */
    public Report probe() {
        return probe(experiment.poolId());
    }

    /**
     * 校验指定号池。
     *
     * <p>加这个重载是因为无参版本读的是<b>实验号池</b>（{@code ExperimentContext} 里一个
     * 默认 1001、只在 {@code /verify/preheat} 时被改写的 AtomicLong）。
     * 那对压测脚本是对的，对<b>对账补偿</b>就完全错了 ——
     * 对账是号源安全的最后一道防线（它能把「人间蒸发」的号补回桶），
     * 而它当时只保护最后一次压测用的那个池子，
     * <b>35 个真实排班的账目从来没有被对过账</b>。
     *
     * <p>这和「运营看板永远显示压测号池」是同一个形状的缺陷，但后果更重：
     * 看板只是显示错了对象，对账是<b>该动账目而没动</b>，
     * 而运营看到「对账已执行」会以为账目查过了。
     */
    public Report probe(long poolId) {
        // 采样前的归还计数。校验读完之后再读一次，变了就说明采样期间有号源在归还，
        // 此时号源守恒等式必然差几个 —— 见下面 stableSample 处的说明。
        double releasesBefore = metrics.slotsReleasedCount();
        int initial = orders.totalSlots(poolId);
        StockRedisRepository.Stats stats = stockRedis.stats(poolId);
        long streamLen = stockRedis.streamLength();
        int orderCount = orders.countHoldingSlots(poolId);
        int soldStock = orders.bookedSlots(poolId);

        long consumed = consumerStats.consumedCount();
        long duplicate = consumerStats.duplicateCount();
        long deadLetter = consumerStats.deadLetterCount();
        long oversoldBlocked = consumerStats.oversoldBlockedCount();

        int globalRemaining = stats.total();
        boolean soldOut = globalRemaining == 0;

        // ① 不超卖
        int oversold = Math.max(0, orderCount - initial);

        // ③ 库存守恒。
        //
        // <b>升级到挂号域之后这条等式必须换算法。</b>原来是：
        //     初始 == 桶剩余 + 实例持有 + 已发出事件数
        // 抽象商品域里成立，因为「卖出去」是终态，事件数就等于被消耗的库存数。
        //
        // 但预约单有六种状态，其中 EXPIRED（超时未支付）和 REFUNDED（退号）会<b>把号源还回桶</b>。
        // 号回了桶就被「桶剩余」计入，而「已发出事件数」不会减少 ——
        // 于是每发生一次退号或超时释放，等式就凭空多出 1。
        // 实测：退一个号之后立刻报残差 -1，正是这个原因。
        //
        // 正确的算法是按「号源现在在谁手里」来配平：
        //     初始 == 桶剩余 + 实例持有 + 占号预约数 + 已发出但还没落库的事件数
        //
        // 其中「占号预约数」只含 PENDING_PAY / BOOKED / COMPLETED / NO_SHOW 四种状态；
        // EXPIRED 和 REFUNDED 刻意不算 —— 它们的号已经在「桶剩余」里了，再算一遍就是重复计数。
        //
        // 注意「已发出未落库」这一项是<b>全局</b>量（Stream 所有号池共用一条），
        // 而其余三项都是本号池的量。所以等式③ <b>只在 Stream 排空时精确</b>——
        // 排空时这一项恒为 0，混口径的问题不显现。
        // 校验器的正常使用方式本来就是「压测结束、消费追平之后再调」，前提成立；
        // 但如果在别的号池有积压时调它，这一项会把别人的在途量算到本池头上。
        // 这里不做更复杂的按池拆分，因为 Stream 本身就是单条设计（全局削峰），
        // 拆成按池会让消费者数量随号池增长——那是个更坏的取舍。
        // 「已处理」<b>只能有一个定义</b>。
        //
        // 原来等式③ 用的是 `consumed + duplicate`，而等式④ 用的是
        // `consumed + duplicate + deadLetter + oversoldBlocked` —— 同一个概念两套算法。
        // 后果是任何一次超卖拦截或死信都会<b>永久</b>抬高③ 的「已发出未落库」，
        // 于是③ 报出一个不存在的<b>负</b>残差，也就是「潜在超卖」告警。
        //
        // 实测：streamLen=173、consumed=153、oversoldBlocked=20 →
        // ③ 算出「560 == 560 + 0 + 0 + 20」，残差 -20，报「占号比总号数多 20 个（潜在超卖）」，
        // 而④ 在同一份报告里显示「173 == 153 + 0 + 20(超卖拦截) + 0 + 0」完全通过。
        // <b>两条等式对着同一批数据给出互相矛盾的结论。</b>
        //
        // 更糟的是它报的是<b>危险的那个方向</b>：运营看到「潜在超卖」必须当真，
        // 而这个告警在任何一次超卖拦截之后就再也不会消失 ——
        // <b>一个永远亮着的红灯，最终会被当成坏了的灯。</b>
        //
        // 所以这里和等式④ 共用同一个 processed，让两者<b>在结构上无法再分叉</b>。
        // 这和 bug ⑮（展示判据与 passed 字段用两套标准）是同一个教训的另一面。
        long processed = consumed + duplicate + deadLetter + oversoldBlocked;
        int notYetPersisted = (int) Math.max(0, streamLen - processed);
        int accounted = stats.bucketSum() + stats.leaseHeld() + orderCount + notYetPersisted;
        int vanished = initial - accounted;

        // ④ 链路守恒。
        //
        // 未处理量必须用「已发出 − 已处理」算，不能用 XPENDING。
        // XPENDING 只统计「已读但未 ACK」的消息，而<b>还没被消费组读取</b>的消息既不算 pending
        // 也不算已处理 —— 拿它当排空判据会导致灾难性的假通过：实测出现过
        // 「发出 75742 条、入库 0 条、pending 0」被判定为「全部等式通过」的情况。
        // 假通过比假失败危险得多。
        //
        // 算术推导的已知缺陷是可能算出负数：消息在落库队列里排队时还没 ACK，
        // 排队时间一旦超过 claim-idle 阈值，claimIdlePending 会把它抢回去再处理一遍，
        // 于是「已处理数」大于「已发出数」。唯一索引保证数据不会错（重复那次被挡掉并计为 duplicate），
        // 所以这里把两个方向分开报：欠的算未处理，多的算重复处理。
        // processed 已在等式③ 处算过，这里复用同一个值 —— 见那里的注释
        long unprocessed = Math.max(0, streamLen - processed);
        long reprocessed = Math.max(0, processed - streamLen);
        long pending = stockRedis.streamPending();

        // ② 不少卖：两个前提都要满足才有意义。
        //
        //   前提一：真的售罄了 —— 还有货时订单少于总量是正常的。
        //   前提二：Stream 已经排空 —— 否则「订单少于库存」只是消费者还没追平，不是少卖。
        //
        // 少了前提二会产生非常有误导性的假警报：实测 10 万库存全部卖光、但还有 6.7 万条消息在队列里时，
        // 这条等式会报「少卖 67497 件」，而实际上一件都没少，只是还没落库。
        // 真正的少卖看等式③ 的守恒残差（库存卡在桶或实例本地余量里），那才是库存凭空消失。
        boolean drained = unprocessed == 0;
        int undersold = (soldOut && drained) ? Math.max(0, initial - orderCount) : 0;

        // 采样是否稳定：读完所有量之后再看一次归还计数。
        //
        // <b>归还号源是「先改 MySQL 状态、再还 Redis 号源」两步操作</b>（这个顺序是刻意的，
        // 见 AppointmentService），中间那个窗口里预约已经不占号、而号还没回到桶里。
        // 只要采样期间有归还在进行，等式③ 就必然差几个 —— 差的正是在途的那几个。
        //
        // 实测踩到过：上一轮压测卖光 2 万个号之后，超时释放任务正在批量把
        // PENDING_PAY 转成 EXPIRED，此时调校验，报「号源凭空消失 1 个（少卖来源）」。
        // 数据完全正确，纯粹是<b>在一个正在变化的系统上做了非原子的多点采样</b>。
        //
        // 这和等式②⑤ 已有的「消费未追平就不判定」是同一个道理：
        // <b>校验器必须能识别「现在不是判定的时机」，否则它会把自己的采样误差报成系统缺陷。</b>
        // 而假警报的代价不只是浪费排查时间 —— 它会让人开始习惯性忽略校验结果。
        double releasesAfter = metrics.slotsReleasedCount();
        boolean stableSample = releasesBefore == releasesAfter;
        int releasesInFlight = (int) Math.round(releasesAfter - releasesBefore);

        List<String> equations = new ArrayList<>();
        equations.add(eq("① 不超卖", orderCount + " ≤ " + initial, oversold == 0,
                oversold == 0 ? "通过" : "超卖 " + oversold + " 件"));
        String eq2Expr = !soldOut ? "（未售罄，本条不适用）"
                : !drained ? "（已售罄但还有 " + unprocessed + " 条未落库，等消费追平后才可判定）"
                : orderCount + " == " + initial;
        equations.add(eq("② 不少卖", eq2Expr,
                undersold == 0, undersold == 0 ? "通过" : "少卖 " + undersold + " 件"));
        // 采样不稳定时不判定 ③。判据和展示用同一个 eq3Ok，不再重复 ⑮ 那个
        //「展示宽松、passed 严格」的错误。
        boolean eq3Ok = vanished == 0 || !stableSample;
        equations.add(eq("③ 号源守恒",
                initial + " == " + stats.bucketSum() + "(桶剩余) + " + stats.leaseHeld() + "(实例持有) + "
                        + orderCount + "(占号预约) + " + notYetPersisted + "(已发出未落库)"
                        + (stableSample ? "" : "（采样期间有 " + releasesInFlight + " 个号源正在归还）"),
                eq3Ok,
                vanished == 0 ? "通过"
                        : !stableSample
                            ? "（采样期间有 " + releasesInFlight + " 个号源正在归还，残差 " + vanished
                                    + " 是在途量造成的采样偏移，暂不判定；等释放任务追平后重跑）"
                        : vanished > 0 ? "号源凭空消失 " + vanished + " 个（少卖来源）"
                        : "占号比总号数多 " + (-vanished) + " 个（潜在超卖）"));
        equations.add(eq("④ 链路守恒",
                streamLen + "(已发出) == " + consumed + "(入库) + " + duplicate + "(重复) + "
                        + oversoldBlocked + "(超卖拦截) + " + deadLetter + "(死信) + " + unprocessed + "(未处理)"
                        + (reprocessed > 0 ? " − " + reprocessed + "(重复处理)" : ""),
                unprocessed == 0,
                unprocessed == 0
                        ? (reprocessed > 0
                            ? "通过（有 " + reprocessed + " 条被重复处理过，已被唯一索引挡掉，不影响正确性）"
                            : "通过")
                        : describeUnprocessed(unprocessed, pending, orderCount, streamLen)));
        // ⑤ 交叉校验。
        //
        // <b>挂号域里必须拆成两组比，不能三个数放一起。</b>原来是
        //     订单数 == sold_stock == 消费入库
        // 抽象商品域成立，因为三者都是「卖出去多少」这一个单调递增的量。
        //
        // 但预约单会退号：占号数会<b>减少</b>，而「消费入库数」是累计量、永远不减。
        // 把它们等值比较，退一个号就必然报不一致。实测：退号后立刻报
        // 「占号预约 3 == booked_slots 4 == 消费入库 4」失败。
        //
        // 正确的分法是按「量的性质」分组：
        //   当前量组：占号预约数 == 排班上的已占号数（两者都随退号减少）
        //   累计量组：已落库预约总数（含各种终态）== 消费入库数（都只增不减）
        //
        // 还有一个前提：<b>累计量那一组只在 preheat 划定过共同起点之后才可比。</b>
        // 「消费入库数」是进程内内存计数器（重启归零），「已落库预约数」查 MySQL（跨重启保留），
        // 两者只有被 preheat 同时归零过才在同一起点上。少了这个判据，应用重启后
        // 随便挂一个号就报「落库预约 24053 == 消费入库 1 不一致」——数据完全正确，
        // 纯粹是口径问题。参见 {@link ExperimentContext#preheated()}。
        //
        // 还有一个<b>口径</b>问题，和上面的时间基线是两个独立的坑：
        // 「消费入库数」是<b>全局</b>计数器（Stream 键 fp:stream:order 只有一条，所有号池共用），
        // 而最初这里用的是 countAllAppts(poolId) —— <b>一个池的行数比所有池的事件数</b>。
        // 压测时全场只有一个池有流量，所以一直是对的；实测踩到的那次是：
        // 压测完在演示排班上挂了**一个**号，立刻报「落库预约 116 == 消费入库 117 不一致」。
        // 数据完全正确，纯粹是口径错。
        //
        // 正确比法：两边都取全局，再减掉 preheat 时的基线 ——
        // preheat 之后新增的每条预约，无论落在哪个号池，都恰好对应一个消费入库事件。
        int allAppts = orders.countAllApptsGlobal() - (int) experiment.apptBaseline();
        boolean comparable = experiment.preheated();
        boolean currentOk = orderCount == soldStock;
        boolean cumulativeOk = !comparable || allAppts == consumed;
        boolean crossOk = currentOk && cumulativeOk;
        // 未追平时不判定：采样时刻不同造成的偏移不算不一致。
        // 这个宽松判据必须和下面 passed 用的是同一个值 —— 见 crossVerdict 处的注释。
        // 归还在途同样会让「占号预约 == 排班已占」差几个：归还先改状态（占号数减）、
        // 再减 booked_slots，两步之间有窗口。所以 stableSample 对这条也适用。
        boolean crossVerdict = crossOk || !drained || !stableSample;
        equations.add(eq("⑤ 交叉校验",
                "当前量：占号预约 " + orderCount + " == 排班已占 " + soldStock
                        + "；累计量：" + (comparable
                                ? "本轮新增预约 " + allAppts + " == 消费入库 " + consumed + "（均为全局口径）"
                                : "（未经 preheat，累计口径不可比，本组不判定）"),
                crossVerdict,
                crossOk ? "通过"
                        : !drained ? "（消费未追平，采样时刻不同导致的偏移，暂不判定）"
                        : !stableSample ? "（采样期间有 " + releasesInFlight + " 个号源正在归还，暂不判定）"
                        : !currentOk ? "当前占号数与排班计数不一致，需排查（归还路径漏减 booked_slots？）"
                        : "落库预约数与消费入库数不一致，需排查"));

        // passed 必须和等式⑤ 展示的结论用<b>同一个判据</b>。
        //
        // 这里踩过一个很隐蔽的坑：原来展示用 `crossOk || !drained`（宽松），
        // 而 passed 用 `crossOk`（严格）。于是出现过报告里五条等式全部显示"通过"、
        // 而 passed 字段是 false 的情况 —— 人读等式以为没问题，CI 读 passed 判定失败，
        // 两边都"正确"却互相矛盾，排查时完全没有线索指向真正的原因。
        // **同一个结论有两套判据，就等于没有结论。**
        // 每一项都用它对应等式展示的那个判据，一个不落 ——
        // 等式③ 用 eq3Ok 而不是 vanished == 0，否则又会出现「③ 显示不判定而 passed=false」。
        boolean passed = oversold == 0 && undersold == 0 && eq3Ok
                && unprocessed == 0 && oversoldBlocked == 0
                && crossVerdict;

        Report report = new Report(poolId, initial, stats.bucketSum(), stats.leaseHeld(), streamLen,
                orderCount, soldStock, consumed, duplicate, deadLetter, oversoldBlocked,
                oversold, undersold, vanished, unprocessed, soldOut, passed, stableSample, equations);

        return report;
    }

    /**
     * 校验并留档：跑一次 {@link #probe()}，把结果写进 {@code t_verify_report} 并打日志。
     *
     * <p>这是给人调的接口（{@code GET /verify/check}、实验脚本）。
     * 自动任务用 {@link #probe()}，避免把留档表冲成噪声。
     */
    public Report check() {
        Report report = probe();
        persist(report);
        if (report.passed()) {
            log.info("[一致性校验] 通过 poolId={} 初始={} 订单={} 桶剩余={}",
                    report.poolId(), report.initialStock(), report.orderCount(), report.bucketSum());
        } else {
            log.error("[一致性校验] 未通过 poolId={} 超卖={} 少卖={} 守恒残差={} 未处理={}",
                    report.poolId(), report.oversold(), report.undersold(),
                    report.vanished(), report.unprocessed());
        }
        return report;
    }

    /**
     * 把「还有多少没处理」翻译成人能看懂的故障模式。
     *
     * <p>之前这里只做一次减法，结果 P6 主从切换实验里打出了 <b>「-261 条还没被读取」</b>：
     * {@code unprocessed} 由 XLEN 推算、{@code pending} 来自 XPENDING，两者不是同一时刻的快照，
     * 而主从切换之后 PEL 里还会残留<b>指向已被删除消息</b>的条目，于是 pending 可以大于 unprocessed。
     * 负数本身不致命，致命的是它掩盖了真正的故障模式。
     *
     * <p>要能区分三种原因，因为处置方式完全不同：
     * <ul>
     *   <li><b>Redis 丢写</b>：MySQL 订单数 &gt; Redis 流长度。只会发生在主从切换之后 ——
     *       从库还没复制完就被提升为主库，旧主库反向同步时丢弃了多出来的流数据。
     *       <b>这不是 bug，而是设计取舍被验证了</b>：Redis 只是预扣器和缓冲，MySQL 才是账本，
     *       所以订单一条没丢，丢的只是 Redis 侧的流水。P6 实测：150950 笔订单 vs 流里 133778 条。</li>
     *   <li><b>PEL 指向已删除消息</b>：pending 超过流里实际存在的量。这些条目永远 ACK 不掉，
     *       XAUTOCLAIM 会反复去捞却捞不到内容，必须显式清理消费组。</li>
     *   <li><b>消费端真的落后</b>：最普通的情况，等它追平即可。</li>
     * </ul>
     */
    private static String describeUnprocessed(long unprocessed, long pending, int orderCount, long streamLen) {
        StringBuilder sb = new StringBuilder("还有 " + unprocessed + " 条未处理");

        long lostWrites = orderCount - streamLen;
        if (lostWrites > 0) {
            sb.append("；【Redis 丢写】MySQL 有 ").append(orderCount)
              .append(" 笔订单，Redis 流里只剩 ").append(streamLen)
              .append(" 条，差 ").append(lostWrites)
              .append(" 条被主从切换丢弃 —— 订单未丢，账本在 MySQL");
        }

        if (pending > streamLen) {
            sb.append("；【PEL 残留】pending=").append(pending)
              .append(" 超过流长度 ").append(streamLen)
              .append("，PEL 里有指向已删除消息的条目，永远 ACK 不掉，需清理消费组");
        } else {
            sb.append("，其中 ").append(pending).append(" 条已读未 ACK、")
              .append(Math.max(0, unprocessed - pending)).append(" 条还没被读取");
        }
        return sb.toString();
    }

    private static String eq(String name, String expression, boolean ok, String note) {
        return String.format("%s  %s  →  %s%s", name, expression, ok ? "✔ " : "✘ ", note);
    }

    private void persist(Report r) {
        try {
            jdbc.update("""
                            INSERT INTO t_consistency_report
                                (item_id, initial_stock, bucket_sum, lease_held, stream_len, order_count,
                                 duplicate, dead_letter, oversold, undersold, passed, detail)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    r.poolId(), r.initialStock(), r.bucketSum(), r.leaseHeld(), (int) r.streamLength(),
                    r.orderCount(), (int) r.duplicate(), (int) r.deadLetter(),
                    r.oversold(), r.undersold(), r.passed() ? 1 : 0,
                    String.join("\n", r.equations()));
        } catch (Exception e) {
            log.warn("校验报告落库失败：{}", e.toString());
        }
    }

    /** 历史报告，用来对比多轮实验。 */
    public List<Map<String, Object>> history(int limit) {
        return jdbc.query("""
                SELECT id, item_id, initial_stock, bucket_sum, lease_held, stream_len, order_count,
                       oversold, undersold, passed,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
                FROM t_consistency_report
                ORDER BY id DESC
                LIMIT ?
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("poolId", rs.getLong("item_id"));
            m.put("initialStock", rs.getInt("initial_stock"));
            m.put("bucketSum", rs.getInt("bucket_sum"));
            m.put("leaseHeld", rs.getInt("lease_held"));
            m.put("streamLen", rs.getInt("stream_len"));
            m.put("orderCount", rs.getInt("order_count"));
            m.put("oversold", rs.getInt("oversold"));
            m.put("undersold", rs.getInt("undersold"));
            m.put("passed", rs.getBoolean("passed"));
            m.put("createdAt", rs.getString("created_at"));
            return m;
        }, limit);
    }
}
