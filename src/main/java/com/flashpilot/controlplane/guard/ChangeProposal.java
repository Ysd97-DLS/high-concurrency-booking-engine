package com.flashpilot.controlplane.guard;

/**
 * 一次参数变更提案。
 *
 * <p><b>为什么要有这个类型</b>：LLM 的输出必须是结构化的、schema 可校验的，
 * 绝不能让它返回自由文本再由我们去正则解析。Agent 那一层强制模型走 tool call，
 * 参数直接映射到这个 record，解析失败就重试或驳回。
 *
 * @param param    参数键名（必须在 {@link com.flashpilot.controlplane.config.ConfigParam} 白名单里）
 * @param value    目标值（护栏会钳制到合法区间）
 * @param source   来源：L0_RULE / L1_AGENT / MANUAL
 * @param reason   为什么改。规则控制器填触发条件，Agent 填归因结论——这一栏是审计的价值所在
 */
public record ChangeProposal(
        String param,
        double value,
        String source,
        String reason
) {

    public static final String SOURCE_RULE = "L0_RULE";
    public static final String SOURCE_AGENT = "L1_AGENT";
    public static final String SOURCE_MANUAL = "MANUAL";

    public static ChangeProposal rule(String param, double value, String reason) {
        return new ChangeProposal(param, value, SOURCE_RULE, reason);
    }

    public static ChangeProposal agent(String param, double value, String reason) {
        return new ChangeProposal(param, value, SOURCE_AGENT, reason);
    }

    public static ChangeProposal manual(String param, double value, String reason) {
        return new ChangeProposal(param, value, SOURCE_MANUAL, reason);
    }
}
