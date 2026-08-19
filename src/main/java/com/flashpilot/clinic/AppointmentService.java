package com.flashpilot.clinic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flashpilot.clinic.domain.Appointment;
import com.flashpilot.clinic.domain.AppointmentRepository;
import com.flashpilot.clinic.domain.ApptStatus;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.dedupe.PurchaseDedupe;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.metrics.SeckillMetrics;

/**
 * 预约单状态机的编排层：支付、退号、超时释放。
 *
 * <p><b>这里所有「改状态 + 归还号源」的操作都必须遵守同一个顺序：先改 MySQL 状态，再还 Redis 号源。</b>
 * 顺序反了会超卖：先把号还回池子、还没改状态时进程挂掉，号被别人抢走，
 * 而这张单在数据库里仍是 PENDING_PAY —— 一个号卖了两次。
 *
 * <p>反过来（先改状态、还没还号就挂掉）只会少卖，而少卖有兜底：
 * 一致性校验器的等式③ 会报出残差，对账任务能把差额加回去。
 * <b>在「可能超卖」和「可能少卖」之间，永远选可能少卖。</b>
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appts;
    private final com.flashpilot.clinic.domain.ApptPersistRepository persist;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;
    private final SeckillMetrics metrics;
    private final PurchaseDedupe dedupe;

    public AppointmentService(AppointmentRepository appts,
                              com.flashpilot.clinic.domain.ApptPersistRepository persist,
                              StockRedisRepository stockRedis,
                              HotConfigService hotConfig, SeckillMetrics metrics,
                              PurchaseDedupe dedupe) {
        this.appts = appts;
        this.persist = persist;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
        this.metrics = metrics;
        this.dedupe = dedupe;
    }

    public enum Result { OK, NOT_FOUND, WRONG_STATE, NOT_REFUNDABLE }

    /**
     * 支付。PENDING_PAY → BOOKED，不涉及号源变动（号本来就被这张单占着）。
     *
     * <p>用带旧状态条件的 UPDATE，所以它和超时释放任务是互斥的：
     * 谁的 affected rows 是 1 谁赢。这就是为什么不需要分布式锁。
     */
    public Result pay(String apptNo) {
        Appointment a = appts.findByApptNo(apptNo);
        if (a == null) {
            return Result.NOT_FOUND;
        }
        if (a.status() != ApptStatus.PENDING_PAY) {
            return Result.WRONG_STATE;
        }
        boolean won = appts.markPaid(apptNo, LocalDateTime.now());
        if (!won) {
            // 说明刚好被超时任务抢先改成 EXPIRED 了。号源已经被它还回池子，
            // 这里绝对不能再当成支付成功，否则就是超卖。
            log.info("支付时发现单据已被超时释放 apptNo={}", apptNo);
            return Result.WRONG_STATE;
        }
        return Result.OK;
    }

    /** 患者退号。BOOKED → REFUNDED，并归还号源。 */
    public Result refund(String apptNo) {
        Appointment a = appts.findByApptNo(apptNo);
        if (a == null) {
            return Result.NOT_FOUND;
        }
        if (a.status() != ApptStatus.BOOKED) {
            return Result.WRONG_STATE;
        }
        if (!a.refundable(LocalDateTime.now())) {
            return Result.NOT_REFUNDABLE;
        }
        if (!appts.markRefunded(apptNo, LocalDateTime.now())) {
            return Result.WRONG_STATE;
        }
        releaseOne(a.scheduleId(), a.patientId(), "退号 apptNo=" + apptNo);
        return Result.OK;
    }

    /** 就诊完成。不涉及号源。 */
    public Result complete(String apptNo) {
        return appts.markCompleted(apptNo) ? Result.OK : Result.WRONG_STATE;
    }

    /**
     * 失约。BOOKED → NO_SHOW，<b>不归还号源</b>。
     *
     * <p>号源的价值是「某时间点某医生的接诊能力」，时间过了就消失了，
     * 还回池子也没人能用。这一点和电商退货回库语义完全不同。
     */
    public Result noShow(String apptNo) {
        return appts.markNoShow(apptNo) ? Result.OK : Result.WRONG_STATE;
    }

    /**
     * 批量把「就诊日已过却仍是 BOOKED」的单标成失约。由 {@link NoShowScanTask} 调。
     *
     * <p><b>这个方法以前不存在，而 NO_SHOW 是六状态机里唯一进不去的状态</b>：
     * {@code markNoShow} 唯一的调用者是手工 HTTP 接口，没有任何定时任务，
     * 而文档明确写着「就诊时段结束扫描」。后果是一条完整的失效链 ——
     * 患者没来就诊 → 单子永远停在 BOOKED → {@code no_show_count} 永不增加
     * → {@code blocked_until} 永不设置 → <b>失约黑名单永远是空的</b>。
     * 而黑名单是整个风控体系里唯一会「真正拒绝」的手段。
     *
     * <p>不归还号源：号源的价值是「某时间点某医生的接诊能力」，时间过了就消失了。
     *
     * @return 实际标记的数量
     */
    public int markNoShowBatch(int batchLimit) {
        // 严格早于今天 —— 也就是「昨天及更早」的就诊日。判据为什么这么保守，
        // 见 AppointmentRepository#findNoShowCandidates：判早了会误伤真实患者，
        // 而失约累计 3 次就是 30 天禁约。
        List<Appointment> candidates = appts.findNoShowCandidates(LocalDate.now(), batchLimit);
        if (candidates.isEmpty()) {
            return 0;
        }
        int marked = 0;
        for (Appointment a : candidates) {
            // markNoShow 是条件更新（WHERE status='BOOKED'），抢不到说明这张单
            // 刚被别的路径改了状态（比如运营手工标记），跳过即可。
            if (appts.markNoShow(a.apptNo())) {
                marked++;
            }
        }
        if (marked > 0) {
            // 这条日志要能回答「有多少人因此被记了失约」——
            // 失约会累积成 30 天禁约，是唯一会真正拒绝患者的处置，规模必须可见。
            log.warn("失约扫描：{} 张就诊日已过的预约单标记为 NO_SHOW（本批扫出 {} 张）。"
                    + "累计失约 3 次的患者会被限制预约 30 天，可在运营看板解封",
                    marked, candidates.size());
        }
        return marked;
    }

    /**
     * 超时释放：把已过支付时限的待支付单转成 EXPIRED，并把号源还回池子。
     *
     * <p>这是「完整项目」相对「压测 demo」最关键的补齐。没有它，
     * 所有没付钱的人都白占着号，号池会被慢慢耗空而实际一个都没卖出去。
     *
     * @return 实际释放的单数
     */
    public int releaseExpired(int batchLimit) {
        List<Appointment> expired = appts.findExpiredPending(LocalDateTime.now(), batchLimit);
        if (expired.isEmpty()) {
            return 0;
        }
        int released = 0;
        int disagreed = 0;
        for (Appointment a : expired) {
            // SQL 只负责粗筛出候选（走 idx_deadline、批量、便宜），
            // **判据的权威是 Appointment.payExpired**。
            //
            // 这一行以前不存在，而 payExpired() 当时<b>没有任何调用者</b> ——
            // 「这张单过期了吗」有两份实现（一份 Java、一份藏在 SQL 字符串里），只有 SQL 那份在跑。
            // 危险不在当下（两者当时一致），在于将来：给 payExpired 加一条规则
            // （比如某类患者宽限 5 分钟）的人会以为改对了，而释放任务根本不看这个方法 ——
            // **号照旧被释放，而代码看起来处理了这个规则**。这和 bug ⑮ 是同一个形状。
            LocalDateTime now = LocalDateTime.now();
            if (!a.payExpired(now)) {
                disagreed++;
                continue;
            }
            // 先改状态。抢到（affected=1）才归还号源，避免和患者支付重复处理同一张单。
            if (!appts.markExpired(a.apptNo(), now)) {
                continue;      // 被支付抢先了，正常情况，跳过
            }
            releaseOne(a.scheduleId(), a.patientId(), "支付超时 apptNo=" + a.apptNo());
            released++;
        }
        if (disagreed > 0) {
            // 不一致必须发声，而且后果要说清楚：这批单每轮都会被扫出来又跳过，
            // **占着单批上限不放**，把后面真正该释放的单挡在外面 —— 表现为号源迟迟不回池子。
            log.error("""
                    超时释放的两个判据不一致：SQL 扫出 {} 张，其中 {} 张 payExpired() 认为没过期。
                    这批单会每轮被反复扫出又跳过，占着单批上限（{}），把后面真正该释放的单挡住。
                    先查两处的时间语义：SQL 是 `pay_deadline < now`，Java 是 `now.isAfter(payDeadline)`，
                    两者本应等价 —— 不等价通常意味着时区、精度（MySQL DATETIME 默认秒级）或时钟问题。""",
                    expired.size(), disagreed, batchLimit);
        }
        if (released > 0) {
            log.info("超时释放 {} 个号源（本批扫出 {} 张待支付超时单）", released, expired.size());
        }
        return released;
    }

    /**
     * 归还一个号源。
     *
     * @param holderId 号的原持有者（患者）。<b>必须传</b>——归还号源和清判重是
     *                 「这个患者在这个排班里已经不占号了」的两半，少任何一半都不成立。
     */
    private void releaseOne(long scheduleId, long holderId, String reason) {
        // 排班上的已占号数要跟着减，否则它会永久漂移 ——
        // 那是患者端「剩余 X 号」的数据源，漂移到最后会显示已满而其实还有号。
        persist.decrementBookedBy(scheduleId, 1);

        int n = stockRedis.releaseSlots(scheduleId, 1);
        if (n != 1) {
            // 归还失败不能静默。它不会造成超卖（号只是没回池子），但会造成少卖，
            // 而少卖在等式③ 里会显示成非零残差，必须能追溯到这条日志。
            log.error("号源归还失败 scheduleId={} 期望 1 实际 {}（{}）—— 会表现为等式③ 残差",
                    scheduleId, n, reason);
        } else {
            metrics.slotReleased();
        }

        // 清一人一单判重。**放在归还号源之后**，和「先改 MySQL 状态再动 Redis」同一条纪律：
        // 提前清掉判重、而后续步骤失败的话，患者能在旧单还占号时再抢一次；
        // 那一次会被 active_key 唯一索引挡在插入阶段（旧单仍是 HOLDING，两者键名相同），
        // 所以不会超卖 —— 但把两个存储的失败窗口收窄总是更好。
        //
        // 反过来这里失败的后果只是「患者暂时约不了这个排班」，可恢复、不涉及号源账目。
        dedupe.clear(scheduleId, holderId);
    }
}
