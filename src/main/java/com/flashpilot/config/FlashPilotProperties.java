package com.flashpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 全部可调参数集中在这里。注意区分两类：
 * <ul>
 *   <li><b>启动期参数</b>（如 stream 的消费者数量）：改了要重启。</li>
 *   <li><b>热参数</b>（如限流阈值、桶数、降级开关）：这里的值只是<i>初始值</i>，
 *       运行时由控制面写进 Redis 并热更新，读取入口是
 *       {@link com.flashpilot.controlplane.config.HotConfigService}，而不是这个类。</li>
 * </ul>
 * 别在热路径上直接读这个类里的热参数，否则控制面调了参数你的代码看不见。
 */
@ConfigurationProperties("flashpilot")
public record FlashPilotProperties(

        @DefaultValue("") String instanceId,
        @DefaultValue Stock stock,
        @DefaultValue Dedupe dedupe,
        @DefaultValue Limit limit,
        @DefaultValue Stream stream,
        @DefaultValue Control control,
        @DefaultValue Clinic clinic
) {

    /** 挂号业务域的参数。 */
    public record Clinic(
            /** 风控频次阈值的初始值。之后由控制面热调，见 ConfigParam.RISK_THRESHOLD。 */
            @DefaultValue("3") int riskThreshold,
            /** 超时释放扫描间隔。 */
            @DefaultValue("3000") long releaseScanMs,
            /**
             * 抢到号之后的支付时限（分钟）。
             *
             * <p>原来是 {@code ApptPersistRepository} 里的一个 {@code private static final int PAY_MINUTES = 10}。
             * 它决定患者有多久付款 —— 是个业务参数，硬编码本身就不合适；
             * 但更要紧的后果是<b>这让超时释放这条路径没法在合理时间内测试</b>：
             * 所有压测都只跑 20–90 秒，而单子要 10 分钟后才到期。
             * 于是「先改 MySQL 状态、再还 Redis 号源」这两步<b>从来没在量级上跑过</b>，
             * 而这条路径出错的方式是<b>双重归还 = 超卖</b>。
             *
             * <p>刻意不做成热参数：改短它会让已经发出的单提前作废。
             */
            @DefaultValue("10") int payMinutes,
            /**
             * 失约扫描间隔。默认 5 分钟 —— 失约统计不急，
             * 而正常情况下候选集是空的（走 idx_visit_status 的窄范围扫描）。
             */
            @DefaultValue("300000") long noShowScanMs,
            /** CMS 滑动窗口轮转间隔。 */
            @DefaultValue("10000") long riskWindowMs,
            /** 失约黑名单刷新间隔。 */
            @DefaultValue("30000") long blocklistRefreshMs,
            /** 对账补偿。 */
            @DefaultValue Reconcile reconcile
    ) {
    }

    /**
     * 对账补偿的参数。
     *
     * <p><b>刻意放在这里而不是热配置白名单里。</b>热参数（8 个）都能被 L1 Agent 提案修改，
     * 而对账开关会直接改动号源账目 —— 让模型能打开它等于给它凭空造号的能力。
     * 护栏管的是「改到什么值」，白名单管的是「能不能碰」，后者是更强的边界。
     * 想调它得改配置重启，这个摩擦是刻意的。
     */
    public record Reconcile(
            /**
             * 是否自动补偿。默认<b>关</b>。
             *
             * <p>默认关是因为它会改账目：一个刚部署、还没建立信任的自动化，
             * 应该先只观察（日志里能看到它<i>会</i>做什么），确认判断正确了再打开。
             */
            @DefaultValue("false") boolean enabled,
            /** 探测间隔。 */
            @DefaultValue("30000") long intervalMs,
            /**
             * 连续观测到<b>同一个</b>残差多少次才动手。
             *
             * <p>3 次 × 30 秒 = 稳定复现 1 分钟以上才补。瞬态残差（消费在途、
             * 租约即将回收）在这个窗口内会自己消失。
             */
            @DefaultValue("3") int consecutiveThreshold,
            /**
             * 单次补偿上限。
             *
             * <p>超过就只告警不动手：这种量级更可能是校验器算错了，
             * 而补偿会把错误放大成超卖。默认 100 相对典型排班（50-120 号）是个保守值。
             */
            @DefaultValue("100") int maxCompensatePerRun
    ) {
    }

    public record Stock(
            @DefaultValue("8") int bucketCount,
            @DefaultValue("20") int segmentSize,
            @DefaultValue("50") int tailThreshold,
            @DefaultValue("10000") long leaseTtlMs,
            @DefaultValue("3000") long heartbeatMs,
            @DefaultValue("2000") long reclaimScanMs
    ) {
    }

    public record Dedupe(
            @DefaultValue("LOCAL") Mode mode
    ) {
        public enum Mode {
            /** 判重放在实例内存，热路径 0 次 Redis 调用，但要求网关按 holderId 粘性路由。 */
            LOCAL,
            /** 判重走 Redis Bitmap，无需粘性路由，代价是每请求一次 RTT。 */
            REDIS
        }
    }

    public record Limit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20000") int permitsPerSecond,
            @DefaultValue("4000") int burst
    ) {
    }

    public record Stream(
            @DefaultValue("fp:stream:order") String key,
            @DefaultValue("fp-order-consumers") String group,
            @DefaultValue("4") int consumerCount,
            @DefaultValue("64") int batchSize,
            @DefaultValue("500") long pollTimeoutMs,
            @DefaultValue("15000") long claimIdleMs,
            @DefaultValue("5") int maxDeliveries,

            // 落库线程数与攒批大小。
            // 批大小是这里最敏感的参数：t_item 的聚合 UPDATE 是单行热点，
            // 批越大行锁获取次数越少、吞吐越高，但一批失败时退回逐条的代价也越大。
            // 256 是实测的折中点。
            @DefaultValue("4") int flusherCount,
            @DefaultValue("256") int flushBatchSize
    ) {
    }

    public record Control(
            @DefaultValue L0 l0,
            @DefaultValue Guard guard,
            @DefaultValue Agent agent
    ) {

        public record L0(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("1000") long intervalMs,
                @DefaultValue("30") double p99SloMs,
                @DefaultValue("0.7") double decreaseFactor,
                @DefaultValue("1000") int increaseStep,
                @DefaultValue("3") int healthyCyclesBeforeIncrease
        ) {
        }

        public record Guard(
                @DefaultValue("5000") long cooldownMs,
                @DefaultValue("0.5") double maxChangeRatio
        ) {
        }

        public record Agent(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("https://api.deepseek.com") String baseUrl,
                @DefaultValue("deepseek-chat") String model,
                @DefaultValue("") String apiKey,
                @DefaultValue("20000") long timeoutMs,
                @DefaultValue("50") double triggerP99Ms,
                @DefaultValue("0.2") double triggerRejectRate,
                @DefaultValue("2000") long triggerStreamPending,
                @DefaultValue("20000") long observeWindowMs
        ) {
        }
    }
}
