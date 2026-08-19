package com.flashpilot.clinic.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.dataplane.stock.StockRedisRepository;

/**
 * 放号服务：把排班上的号灌进号池。支持一次性和<b>分批</b>两种节奏。
 *
 * <p><b>分批放号是挂号这个垂类最有业务价值的技术点。</b>
 * 真实医院普遍在固定时刻（6:00 / 7:00 / 提前 7 天 8:00）统一放号，
 * 于是几万人在同一秒争抢几十个专家号。与其一秒放完引发洪峰，不如在几分钟内分批放出：
 * <ul>
 *   <li><b>削峰</b>：同样的号总量，峰值 QPS 能降一个数量级；</li>
 *   <li><b>反脚本</b>：脚本盯着整点打一轮就走，分批放出后它必须持续轮询几分钟才能抢到，
 *       成本显著上升，而真实患者本来就会在页面上多刷几次；</li>
 *   <li><b>可调</b>：批次间隔由控制面的 {@link ConfigParam#RELEASE_SPREAD_SECONDS} 决定，
 *       压力大就放慢、压力小就加快。</li>
 * </ul>
 *
 * <p><b>进度必须记在 {@code released_slots} 而不是靠 Redis 桶余量推算。</b>
 * 桶里的数字会被抢号扣减、被退号加回，它反映「现在还剩多少」；
 * 而放号进度要的是「累计放出多少」。有成交之后两者就不再相等，
 * 混用会导致分批放号重复灌号——那就是超卖。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    /** 分批放号的批次间隔。间隔太小削峰效果有限，太大则患者等太久。 */
    private static final long BATCH_INTERVAL_MS = 2000;

    private final JdbcTemplate jdbc;
    private final StockRedisRepository stockRedis;
    private final HotConfigService hotConfig;

    /** 正在分批放号的排班：scheduleId → 计划。 */
    private final Map<Long, Plan> active = new ConcurrentHashMap<>();

    private record Plan(long scheduleId, int total, int perBatch, long startedAtMs) {
    }

    public ReleaseService(JdbcTemplate jdbc, StockRedisRepository stockRedis, HotConfigService hotConfig) {
        this.jdbc = jdbc;
        this.stockRedis = stockRedis;
        this.hotConfig = hotConfig;
    }

    /**
     * 开始放号。
     *
     * <p>{@code spreadSeconds} 为 0 时一次性放完（用于测试或号量很少的排班）；
     * 大于 0 时按 {@link #BATCH_INTERVAL_MS} 的节奏分批放出，总耗时约等于 spreadSeconds。
     *
     * @return 本次操作的说明
     */
    public Map<String, Object> open(long scheduleId) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT total_slots, released_slots, status FROM t_schedule WHERE id=?
                """, scheduleId);
        if (rows.isEmpty()) {
            r.put("ok", false);
            r.put("message", "排班不存在：" + scheduleId);
            return r;
        }
        int total = ((Number) rows.get(0).get("total_slots")).intValue();
        int released = ((Number) rows.get(0).get("released_slots")).intValue();
        if (released >= total) {
            r.put("ok", false);
            r.put("message", "该排班的号已全部放出（" + released + "/" + total + "），如需重放请先重置");
            return r;
        }

        int spread = hotConfig.getInt(ConfigParam.RELEASE_SPREAD_SECONDS);
        int remaining = total - released;

        jdbc.update("UPDATE t_schedule SET status='OPEN' WHERE id=?", scheduleId);

        if (spread <= 0) {
            int n = releaseBatch(scheduleId, remaining);
            log.info("[放号] 一次性放出 scheduleId={} 数量={}", scheduleId, n);
            r.put("ok", true);
            r.put("mode", "一次性");
            r.put("released", n);
            r.put("message", "已一次性放出 " + n + " 个号");
            return r;
        }

        // 分批：把剩余量分到 spread 秒里，每 BATCH_INTERVAL_MS 放一批
        int batches = Math.max(1, (int) (spread * 1000L / BATCH_INTERVAL_MS));
        int perBatch = Math.max(1, (int) Math.ceil(remaining / (double) batches));
        active.put(scheduleId, new Plan(scheduleId, total, perBatch, System.currentTimeMillis()));
        log.info("[放号] 开始分批放号 scheduleId={} 剩余={} 批次={} 每批={} 预计{}秒放完",
                scheduleId, remaining, batches, perBatch, spread);
        r.put("ok", true);
        r.put("mode", "分批");
        r.put("batches", batches);
        r.put("perBatch", perBatch);
        r.put("spreadSeconds", spread);
        r.put("message", "已开始分批放号，预计 " + spread + " 秒内放完 " + remaining + " 个号");
        return r;
    }

    /** 分批放号的执行器。 */
    @Scheduled(fixedDelay = BATCH_INTERVAL_MS)
    public void tickBatches() {
        if (active.isEmpty()) {
            return;
        }
        for (Plan p : active.values()) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT total_slots, released_slots FROM t_schedule WHERE id=?
                        """, p.scheduleId());
                if (rows.isEmpty()) {
                    active.remove(p.scheduleId());
                    continue;
                }
                int total = ((Number) rows.get(0).get("total_slots")).intValue();
                int released = ((Number) rows.get(0).get("released_slots")).intValue();
                int remaining = total - released;
                if (remaining <= 0) {
                    active.remove(p.scheduleId());
                    log.info("[放号] 分批放号完成 scheduleId={} 共放出 {} 个，耗时 {} 秒",
                            p.scheduleId(), released, (System.currentTimeMillis() - p.startedAtMs()) / 1000);
                    continue;
                }
                int amount = Math.min(p.perBatch(), remaining);
                int n = releaseBatch(p.scheduleId(), amount);
                log.debug("[放号] 批次放出 scheduleId={} 本批={} 累计={}/{}",
                        p.scheduleId(), n, released + n, total);
            } catch (Exception e) {
                log.warn("[放号] 批次失败 scheduleId={}，下个周期重试：{}", p.scheduleId(), e.toString());
            }
        }
    }

    /**
     * 放出一批号：<b>先在 MySQL 预留进度，再把号推进 Redis 桶。</b>
     *
     * <p>顺序不能反，这里踩过一个真实的坑。原来是「先推 Redis，再累加进度」，
     * 而累加带 {@code released_slots + n <= total_slots} 的上限条件，
     * 于是一旦条件不满足（并发放号、分批任务重复执行、运营连点两次开始放号），
     * 就出现<b>号已经进了 Redis 桶、但 MySQL 拒绝记账</b>的局面。
     * 原代码甚至在日志里承认了这个后果（"会表现为号源守恒等式的负残差"）却没有改顺序。
     *
     * <p>那个顺序的实际风险是<b>超卖</b>：Redis 桶里凭空多出的号会被真实患者抢到，
     * 最后只能靠 {@code incrementBookedBy} 的 {@code booked_slots + ? <= total_slots}
     * 兜底挡掉，表现为患者点了「抢号成功」却拿不到号。
     *
     * <p>反过来「先预留、再推送」的最坏情况只是<b>少卖</b>：
     * 进度记了但号没进桶，患者看到的号少几个。这和
     * {@code AppointmentService} 里「先改 MySQL 状态、再还 Redis 号源」是同一条准则——
     * <b>两个存储之间没有事务时，就让失败落在少卖那一侧</b>，因为少卖可恢复、可被等式③ 发现，
     * 而超卖是直接对患者违约。
     *
     * @return 实际放出的号数（0 表示已放满或被并发抢先）
     */
    // 注意签名里<b>没有</b> activeBuckets：桶范围由 StockRedisRepository 统一执行。
    // 留一个参数在这里，就是留一个「可以在这里控制桶范围」的假象 ——
    // 而三个调用方各自决定传什么，正是那个 bug 的成因。
    private int releaseBatch(long scheduleId, int amount) {
        if (amount <= 0) {
            return 0;
        }
        // ① 先预留。条件更新是原子的，并发和重复执行都不会让放出量超过 total_slots。
        int updated = jdbc.update("""
                UPDATE t_schedule SET released_slots = released_slots + ?
                 WHERE id = ? AND released_slots + ? <= total_slots
                """, amount, scheduleId, amount);
        if (updated != 1) {
            log.warn("[放号] 预留被拒 scheduleId={} amount={} —— 已放满或被并发抢先，"
                    + "本批不放号（号没有进 Redis，数据仍然一致）", scheduleId, amount);
            return 0;
        }
        // ② 再推进 Redis。失败就把预留还回去，否则进度会虚高、剩下的号再也放不出来。
        try {
            int n = stockRedis.releaseSlots(scheduleId, amount);
            if (n < amount) {
                unreserve(scheduleId, amount - n);
                log.warn("[放号] Redis 实际放出 {} 少于预留 {}，差额已回滚 scheduleId={}",
                        n, amount, scheduleId);
            }
            return n;
        } catch (RuntimeException e) {
            unreserve(scheduleId, amount);
            log.error("[放号] 推进 Redis 失败，预留已回滚 scheduleId={} amount={}：{}",
                    scheduleId, amount, e.toString());
            throw e;
        }
    }

    /** 回滚放号预留。用 GREATEST 兜底，避免任何情况下把进度减成负数。 */
    private void unreserve(long scheduleId, int n) {
        if (n <= 0) {
            return;
        }
        jdbc.update("""
                UPDATE t_schedule SET released_slots = GREATEST(0, released_slots - ?) WHERE id = ?
                """, n, scheduleId);
    }

    /** 停止放号：把状态改回 CLOSED 并取消未完成的分批计划。剩余的号不再放出。 */
    public Map<String, Object> close(long scheduleId) {
        active.remove(scheduleId);
        jdbc.update("UPDATE t_schedule SET status='CLOSED' WHERE id=?", scheduleId);
        log.info("[放号] 已停止 scheduleId={}", scheduleId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("message", "已停止放号，剩余号不再放出");
        return r;
    }

    /** 正在分批放号的排班数，看板用。 */
    public int activePlanCount() {
        return active.size();
    }

    public Map<String, Object> progress(long scheduleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT total_slots, released_slots, booked_slots, status FROM t_schedule WHERE id=?
                """, scheduleId);
        Map<String, Object> r = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            r.put("found", false);
            return r;
        }
        Map<String, Object> row = rows.get(0);
        int total = ((Number) row.get("total_slots")).intValue();
        int released = ((Number) row.get("released_slots")).intValue();
        r.put("found", true);
        r.put("totalSlots", total);
        r.put("releasedSlots", released);
        r.put("bookedSlots", row.get("booked_slots"));
        r.put("status", row.get("status"));
        r.put("releasing", active.containsKey(scheduleId));
        r.put("progressPercent", total == 0 ? 0 : Math.round(released * 1000.0 / total) / 10.0);
        return r;
    }
}
