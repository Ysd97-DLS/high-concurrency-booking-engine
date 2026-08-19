package com.flashpilot.controlplane.l1;

/**
 * Agent 一次决策的结构化输出。
 *
 * <p>模型<b>只能</b>通过 tool call 产出这个结构，我们不解析自由文本。
 * 这不是洁癖：自由文本要靠正则去抠数字，模型措辞一变解析就崩，
 * 而 tool call 的参数有 schema，解析失败可以直接重试或驳回。
 *
 * @param diagnosis 归因结论——这是 Agent 相对规则控制器的核心增量，会写进审计表
 * @param noChange  模型判断当前无需调整
 * @param param     要改的参数键名（仍然要过白名单，模型说什么不算）
 * @param value     目标值（仍然要过区间钳制和幅度限制）
 * @param reason    为什么这么改
 */
public record AgentDecision(
        String diagnosis,
        boolean noChange,
        String param,
        Double value,
        String reason
) {

    public static AgentDecision noChange(String diagnosis) {
        return new AgentDecision(diagnosis, true, null, null, null);
    }

    public static AgentDecision change(String diagnosis, String param, double value, String reason) {
        return new AgentDecision(diagnosis, false, param, value, reason);
    }
}
