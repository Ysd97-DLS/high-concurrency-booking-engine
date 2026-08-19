package com.flashpilot.config;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.stereotype.Component;

/**
 * 启动时验证「调度池容量 >= 定时任务数」这条不变量。
 *
 * <h2>为什么需要机器来查</h2>
 *
 * 这条不变量原来只写在 {@code application.yml} 的注释里，附带一句「当前 N 个」的人工计数。
 * 而那个数字<b>漂移过两次</b>：
 * <ul>
 *   <li>第一版写「一共 8 个」，等到实测时已经是 14 个 —— 配置的 size 恰好也是 8，
 *       也就是说<b>不变量当时其实已经被违反了</b>，只是负载不高没被饿到
 *       （实测 1 秒级任务的滞后中位 619ms、最大 673ms）。</li>
 *   <li>修那次的时候把注释改成「14 个」，而真实数字随后又变了。</li>
 * </ul>
 *
 * <p>问题的形态很清楚：<b>注释里的数字是写代码那一刻的事实，之后每加一个任务都在让它变得更错，
 * 而没有任何机制会提醒你回来改。</b>加任务的人不会去读那段注释，
 * 而池子不够时的后果是<b>定时任务被静默饿死 —— 不报错、不告警、日志里什么都没有</b>。
 * 真正的后果是 AIMD 控制器在系统最需要它的时候停止反应、指标采集停止刷新。
 *
 * <p>所以这个类做的事很简单：启动时问 Spring「你到底注册了几个定时任务」，
 * 和池子大小比一下。<b>把一条靠人记的约定变成一条会自己喊的断言。</b>
 * 从此注释里那个「当前 N 个」是不是准确都不再要紧了。
 *
 * <h2>为什么在 ApplicationReadyEvent 而不是 @PostConstruct</h2>
 *
 * {@code @Scheduled} 的注册由 {@code ScheduledAnnotationBeanPostProcessor} 完成，
 * 它要等所有 bean 都初始化完。在 {@code @PostConstruct} 里数会数到一个偏小的值 ——
 * 而偏小意味着<b>误判为安全</b>，正好是这里最不能出的错。
 */
@Component
public class SchedulerCapacityCheck {

    private static final Logger log = LoggerFactory.getLogger(SchedulerCapacityCheck.class);

    /**
     * 池子大小与任务数相等时也告警。
     *
     * <p>相等不是安全状态：所有任务同时到点时，一个慢任务就会让其它任务排队等待。
     * 而这些任务里有 1 秒级的控制器和指标采集 —— 它们迟到就等于控制面失灵。
     * 留出余量是必须的，这里要求至少多 2 个。
     */
    private static final int MIN_HEADROOM = 2;

    private final Collection<ScheduledTaskHolder> holders;
    private final Environment env;

    public SchedulerCapacityCheck(Collection<ScheduledTaskHolder> holders, Environment env) {
        this.holders = holders;
        this.env = env;
    }

    /** 判定结果。抽成枚举是为了让判定逻辑能不起 Spring 就测 —— 守不变量的东西自己出错就白搭了。 */
    public enum Verdict {
        /** 池子比任务数还少：会有任务被静默饿死。 */
        INSUFFICIENT,
        /** 够用但余量不足：所有任务同时到点时会互相排队。 */
        THIN,
        OK
    }

    /**
     * 纯判定，不碰 Spring。
     *
     * <p>{@code poolSize == tasks} 刻意算 THIN 而不是 OK：相等不是安全状态 ——
     * 所有任务同时到点时，一个慢任务就会让其它任务排队等待，
     * 而这些任务里有 1 秒级的控制器和指标采集，它们迟到就等于控制面失灵。
     */
    static Verdict judge(int tasks, int poolSize, int minHeadroom) {
        if (tasks <= 0) {
            // 没有定时任务时余量要求毫无意义 —— 余量存在的理由是「任务之间不互相排队」，
            // 没有任务就没有排队。这条分支是单元测试逼出来的：
            // 少了它，一个没有任何定时任务的部署会在启动日志里收到一条无意义的告警，
            // 而**无意义的告警会训练人忽略这一类告警**，最终连真的那条也一起被忽略。
            return Verdict.OK;
        }
        if (poolSize < tasks) {
            return Verdict.INSUFFICIENT;
        }
        return (poolSize - tasks < minHeadroom) ? Verdict.THIN : Verdict.OK;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        int tasks = 0;
        for (ScheduledTaskHolder h : holders) {
            tasks += h.getScheduledTasks().size();
        }
        // 默认值和 Spring Boot 的默认调度池一致（1）。如果这里读不到配置，
        // 说明用的是默认单线程调度器 —— 那比配小了更危险。
        int poolSize = env.getProperty("spring.task.scheduling.pool.size", Integer.class, 1);
        Verdict v = judge(tasks, poolSize, MIN_HEADROOM);

        if (v == Verdict.INSUFFICIENT) {
            log.error("""
                    ════════ 调度池容量不足 ════════
                    定时任务 {} 个，而调度池只有 {} 个线程。
                    池子小于任务数时，一个慢任务就会饿死其它任务，而 **被饿死的定时任务不会报错** ——
                    真正的后果是 AIMD 控制器在系统最需要它的时候停止反应、指标采集停止刷新，
                    而日志里什么都没有。
                    改 application.yml 的 spring.task.scheduling.pool.size，建议至少 {}。
                    ══════════════════════════════""",
                    tasks, poolSize, tasks + MIN_HEADROOM);
            return;
        }
        if (v == Verdict.THIN) {
            log.warn("调度池余量偏薄：{} 个任务 / {} 个线程（余 {}）。"
                    + "所有任务同时到点时一个慢任务就会让其它任务排队，"
                    + "而其中有 1 秒级的控制器和指标采集 —— 迟到就等于控制面失灵。建议至少 {}",
                    tasks, poolSize, poolSize - tasks, tasks + MIN_HEADROOM);
            return;
        }
        log.info("调度池容量检查通过：{} 个定时任务 / {} 个线程（余 {}）", tasks, poolSize, poolSize - tasks);
    }
}
