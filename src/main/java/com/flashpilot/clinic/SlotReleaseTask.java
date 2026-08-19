package com.flashpilot.clinic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时释放扫描。把过了支付时限的待支付单转成 EXPIRED 并归还号源。
 *
 * <p><b>为什么用定时扫描而不是延迟队列</b>：延迟队列（Redis ZSet / RocketMQ 延迟消息）
 * 精度更好，但多一个必须保证可靠的组件——消息丢了就永久漏释放一个号源。
 * 定时扫表走 {@code idx_deadline} 索引，代价是最多晚 {@code scanIntervalMs} 释放，
 * 收益是<b>它是自愈的</b>：这一轮漏了下一轮还会扫到，进程重启也不丢。
 * 号源释放晚几秒完全可以接受，永久漏释放不行。
 *
 * <p>批量上限的意义：放号高峰过后会有一大批单同时到期（大家都是同一秒抢的号），
 * 不设上限的话单次扫描会拉出几万行并在一个循环里逐个 UPDATE，把连接池占满。
 * 分批处理，<b>一批跑满就立刻接着跑下一批</b>（见 {@link #MAX_ROUNDS_PER_TICK}），
 * 追平了才等下一个周期。
 */
@Component
public class SlotReleaseTask {

    private static final Logger log = LoggerFactory.getLogger(SlotReleaseTask.class);

    private static final int BATCH_LIMIT = 500;

    /**
     * 一次调度里最多连续跑几批。
     *
     * <p><b>有积压时不该等。</b>{@code fixedDelay} 的语义是「上一次<i>结束</i>后再等 N 秒」，
     * 所以周期 = 处理耗时 + 间隔。实测 6 万张单同时到期时：每批 500 个耗时约 1.28 秒，
     * 加上 3 秒空等 → 相邻批间隔 <b>4.28 秒</b>，实际吞吐只有 117 个/秒，
     * 而「单批 500 / 间隔 3 秒」给人的印象是 167 个/秒。
     * 那一轮里<b>连续 88 批全部触达上限</b> —— 日志一直在喊「仍有积压」，而系统就是不加快。
     *
     * <p>代价对挂号业务是真实的：6 万张单要 8.5 分钟才回到池子，
     * 加上 10 分钟支付时限，热门号从被占到可再售约 18 分钟，<b>其中一半是纯粹的调度空转</b>。
     * 放号高峰后大批未付款单同时到期，正是这个场景。
     *
     * <p>所以改成：本批跑满就立刻接着跑下一批，直到追平。
     * 但必须有上限 —— 这个任务跑在共享调度池里，一直占着线程会饿死其它定时任务，
     * 而<b>被饿死的定时任务不会报错</b>（调度池那条容量不变量就是这么来的）。
     * 取 10 批（约 13 秒）：足够一次吃掉 5000 个积压，又不至于长时间霸占线程。
     */
    private static final int MAX_ROUNDS_PER_TICK = 10;

    private final AppointmentService appointments;

    public SlotReleaseTask(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @Scheduled(fixedDelayString = "${flashpilot.clinic.release-scan-ms:3000}")
    public void scan() {
        try {
            int rounds = 0;
            int total = 0;
            while (rounds < MAX_ROUNDS_PER_TICK) {
                int released = appointments.releaseExpired(BATCH_LIMIT);
                rounds++;
                total += released;
                if (released < BATCH_LIMIT) {
                    return;      // 追平了，等下一个周期
                }
            }
            // 连着跑满 10 批还有积压。这时候才值得喊一声 ——
            // 原来是每批都喊，实测一轮刷了 88 条一模一样的 WARN，
            // 而真正该引起注意的「跑满了还追不平」反而淹没在里面。
            log.warn("超时释放连续 {} 批跑满（共 {} 个）仍有积压，让出线程等下个周期。"
                    + "持续出现说明释放能力跟不上到期速度，考虑加大单批上限或批量化归还",
                    rounds, total);
        } catch (Exception e) {
            log.warn("超时释放扫描失败，下个周期重试：{}", e.toString());
        }
    }
}
