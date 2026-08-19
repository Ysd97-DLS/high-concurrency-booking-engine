package com.flashpilot.controlplane.l1;

import java.util.Optional;

/**
 * LLM 调用的抽象。抽出接口有三个实际好处：
 * <ul>
 *   <li>没配 API key 时能干净地降级（{@link #available()} 返回 false，系统只跑 L0）；</li>
 *   <li>做 A/B 实验时可以换不同模型档位，甚至换成本地小模型；</li>
 *   <li>单元测试里可以塞一个假实现，不用真的调外部服务。</li>
 * </ul>
 */
public interface LlmClient {

    /** 是否可用。不可用时控制面只跑 L0 规则层，这是设计好的降级路径而不是故障。 */
    boolean available();

    /** 模型标识，写进审计和日志，实验时用来区分不同档位。 */
    String model();

    /**
     * 让模型做一次决策。
     *
     * @param systemPrompt 角色与约束（能改哪些参数、区间是多少、目标是什么）
     * @param userPayload  当前指标摘要 + 最近变更历史，JSON 字符串
     * @return 结构化决策；调用失败或模型没走 tool call 时返回空——此时什么都不做，
     *         由 L0 继续兜底
     */
    Optional<AgentDecision> decide(String systemPrompt, String userPayload);
}
