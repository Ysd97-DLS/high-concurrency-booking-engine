package com.flashpilot.clinic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失约扫描。把「就诊日已经过去、却仍是 BOOKED」的预约单转成 NO_SHOW 并累加患者失约次数。
 *
 * <h2>为什么这个类是补上来的</h2>
 *
 * NO_SHOW 是六状态机里<b>唯一进不去的状态</b>。{@code markNoShow} 原来唯一的调用者是
 * 手工接口 {@code POST /clinic/appointments/{apptNo}/no-show}，没有任何定时任务 ——
 * 而文档（DOMAIN.md §3.2、实验报告第 04 节的状态转移表）明确写着「就诊时段结束扫描」。
 *
 * <p>后果是一条完整的失效链，每一环都实现了，只差把它们连起来的这个任务：
 * <pre>
 * 患者没来就诊 → 单子永远停在 BOOKED → no_show_count 永不增加
 *              → blocked_until 永不设置 → 失约黑名单永远是空的
 * </pre>
 * 而黑名单是整个风控体系里<b>唯一会「真正拒绝」的手段</b>——
 * 频次判据命中只是降权进慢车道（仍然能抢，只是慢），只有失约黑名单才直接拒。
 * 实测确认过：{@code t_patient} 里 {@code no_show_count > 0} 的有 0 个。
 *
 * <p>这个缺陷的形态值得记：<b>所有零件都在</b>——状态枚举、条件更新 SQL、
 * 失约计数、30 天禁约的 CASE 表达式、黑名单的内存快照、看板显示、运营解封接口 ——
 * 只缺一个每天跑一次的循环。而文档描述的是一个完整的功能，
 * 所以读文档、读代码、看看板都发现不了，只有去查「这张表里到底有没有数据」才会发现。
 *
 * <h2>两个刻意保守的选择</h2>
 *
 * <p><b>判据只看「就诊日已过」，不精确到时段结束时间。</b>
 * 判早了会把还能来就诊的患者标成失约，而失约累计 3 次就是 30 天禁约 ——
 * 被误封的患者不会投诉，只是挂不上号。判晚了只是统计晚几个小时生效，没有业务影响。
 * 精确到 AM 12:00 / PM 17:30 能早几个小时，但那点提前量换不来任何东西。
 *
 * <p><b>扫描间隔取分钟级而不是秒级。</b>失约统计不急，而这张表会长到几十万行。
 * 正常情况下候选集是空的（走 {@code idx_visit_status} 的窄范围扫描，代价极低），
 * 真正有活干的只是每天 0 点后的那几轮。
 *
 * <h2>第一次上线时会扫到历史积压</h2>
 *
 * 一个已经跑了很久的系统，攒下的 BOOKED 单可能很多，第一次跑会<b>一次性给很多患者记失约</b>，
 * 其中一部分会直接跨过 3 次门槛被封 30 天。所以：批量上限 + 每轮都把规模打进日志，
 * 让运维在第一次上线时能看见量级、必要时用看板的解封功能兜住。
 * 这不是假想风险 —— 「批量惩罚真实用户」是这个功能唯一的严重失败方向。
 */
@Component
public class NoShowScanTask {

    private static final Logger log = LoggerFactory.getLogger(NoShowScanTask.class);

    /**
     * 单批上限。每张单是 2 次 UPDATE（改状态 + 累加失约计数），
     * 500 张约等于 1000 次往返，和超时释放同一个量级。
     */
    private static final int BATCH_LIMIT = 500;

    /**
     * 一次调度里最多连续跑几批。
     *
     * <p>和超时释放同一个道理：有积压时不该空等 ——
     * {@code fixedDelay} 的语义是「上一次<i>结束</i>后再等 N 秒」，
     * 那 N 秒是为「没积压时省资源」准备的，不该在追赶积压时也照付。
     * 上限的存在是因为这个任务跑在共享调度池里，
     * 一直占着线程会饿死其它定时任务，而<b>被饿死的定时任务不会报错</b>。
     */
    private static final int MAX_ROUNDS_PER_TICK = 4;

    private final AppointmentService appointments;

    public NoShowScanTask(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @Scheduled(fixedDelayString = "${flashpilot.clinic.no-show-scan-ms:300000}")
    public void scan() {
        try {
            int rounds = 0;
            int total = 0;
            while (rounds < MAX_ROUNDS_PER_TICK) {
                int marked = appointments.markNoShowBatch(BATCH_LIMIT);
                rounds++;
                total += marked;
                if (marked < BATCH_LIMIT) {
                    return;      // 追平了
                }
            }
            log.warn("失约扫描连续 {} 批跑满（共 {} 张）仍有积压，让出线程等下个周期。"
                    + "首次上线时出现是正常的（历史 BOOKED 单一次性结算）；"
                    + "长期出现说明有大量患者持续不来就诊，那是业务问题不是技术问题",
                    rounds, total);
        } catch (Exception e) {
            // 旁路任务，失败绝不能影响任何业务路径
            log.warn("失约扫描失败，下个周期重试：{}", e.toString());
        }
    }
}
