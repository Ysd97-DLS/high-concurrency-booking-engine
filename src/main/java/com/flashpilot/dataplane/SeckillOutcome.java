package com.flashpilot.dataplane;

/**
 * 秒杀请求的结果。刻意把「被限流」和「售罄」分开，因为这两者对控制面的含义完全不同：
 * 售罄是正常终局，被限流是<b>误拒</b>——控制面要优化的正是后者。
 */
public enum SeckillOutcome {

    SUCCESS(200, "抢购成功，订单正在生成"),
    SOLD_OUT(4001, "已售罄"),
    ALREADY_BOUGHT(4002, "每人限购一件"),
    RATE_LIMITED(4290, "当前排队人数过多，请稍后再试"),
    /**
     * 被风控降权后，慢车道也没有余量。
     *
     * <p><b>刻意和 {@link #RATE_LIMITED} 分开，尽管对用户的提示几乎一样。</b>
     * 原来两者共用 4290，后果是压测报告把 52 万次风控丢弃显示成「限流拒绝」，
     * 整轮性能数字被污染而报告上看不出任何异常——
     * 你以为在测引擎的吞吐，实际测的是慢车道的速率。
     *
     * <p>两个不同原因导致的同一种外部表现，<b>在可观测性上必须可分</b>。
     * 用户看到的话可以一样，但指标、日志、压测统计必须能区分，
     * 否则任何以此为输入的判断（自动控制、性能结论）都在对错误的信号做反应。
     */
    RISK_DEMOTED(4291, "您的操作过于频繁，已进入排队通道，请稍后再试"),
    /** 命中失约黑名单。累计失约 3 次会被限制预约 30 天。 */
    RISK_BLOCKED(4030, "您有多次失约记录，暂时无法预约"),
    INTERNAL_ERROR(5000, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;

    SeckillOutcome(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public boolean ok() {
        return this == SUCCESS;
    }
}
