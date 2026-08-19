package com.flashpilot.controlplane.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.controlplane.MetricsCollector;
import com.flashpilot.controlplane.MetricsSnapshot;
import com.flashpilot.controlplane.config.ConfigAuditRepository;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.controlplane.guard.ChangeProposal;
import com.flashpilot.controlplane.guard.GuardRail;
import com.flashpilot.controlplane.l1.AgentLoop;
import com.flashpilot.controlplane.l1.AgentTools;

/**
 * 控制面的对外接口，也是做 demo 时主要看的东西。
 *
 * <p>{@code /mcp/*} 两个接口是工具层的 HTTP 暴露：
 * {@code /mcp/tools} 对应 MCP 的 {@code tools/list}，{@code /mcp/call} 对应 {@code tools/call}。
 * 换成标准 MCP 的 stdio / SSE 传输是机械工作，属于 W5 的任务。
 */
@RestController
public class ControlPlaneController {

    private final MetricsCollector collector;
    private final HotConfigService hotConfig;
    private final GuardRail guard;
    private final ConfigAuditRepository audit;
    private final AgentLoop agentLoop;
    private final AgentTools tools;

    public ControlPlaneController(MetricsCollector collector, HotConfigService hotConfig, GuardRail guard,
                                  ConfigAuditRepository audit, AgentLoop agentLoop, AgentTools tools) {
        this.collector = collector;
        this.hotConfig = hotConfig;
        this.guard = guard;
        this.audit = audit;
        this.agentLoop = agentLoop;
        this.tools = tools;
    }

    @GetMapping("/control/metrics")
    public MetricsSnapshot metrics() {
        return collector.latest();
    }

    @GetMapping("/control/config")
    public Map<String, Object> config() {
        Map<String, Object> bounds = new LinkedHashMap<>();
        for (ConfigParam p : ConfigParam.values()) {
            bounds.put(p.key(), Map.of("min", p.min(), "max", p.max()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", hotConfig.version());
        m.put("values", hotConfig.snapshot());
        m.put("bounds", bounds);
        return m;
    }

    /**
     * 人工改参数。注意它同样要过护栏（白名单 + 钳制 + 幅度限制），
     * 只是可以绕过冷却期——出事时人必须能立刻干预。
     */
    @PostMapping("/control/config")
    public GuardRail.GuardResult setConfig(@RequestParam String param,
                                           @RequestParam double value,
                                           @RequestParam(defaultValue = "人工调整") String reason,
                                           @RequestParam(defaultValue = "false") boolean dryRun) {
        return guard.submit(ChangeProposal.manual(param, value, reason), dryRun);
    }

    @PostMapping("/control/config/rollback")
    public Map<String, Object> rollback() {
        return Map.of("result", hotConfig.rollbackLast().orElse("没有可回滚的变更"));
    }

    @GetMapping("/control/audit")
    public List<ConfigAuditRepository.Entry> auditLog(@RequestParam(defaultValue = "30") int limit) {
        return audit.recent(limit);
    }

    // ---------- Agent ----------

    @GetMapping("/control/agent/timeline")
    public Map<String, Object> agentTimeline() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", agentLoop.enabled());
        m.put("hint", agentLoop.enabled()
                ? "Agent 已启用。指标越界时会自动唤醒，也可以 POST /control/agent/tick 手工触发。"
                : "Agent 未启用（缺 LLM_API_KEY 或 flashpilot.control.agent.enabled=false），系统当前只跑 L0 规则控制。");
        m.put("timeline", agentLoop.timeline());
        return m;
    }

    /** 手工触发一次 Agent 决策。{@code dryRun=true} 时只看提案不执行，做 demo 很好用。 */
    @PostMapping("/control/agent/tick")
    public Map<String, Object> agentTick(@RequestParam(defaultValue = "false") boolean dryRun) {
        return agentLoop.tickNow(dryRun);
    }

    // ---------- 工具层（MCP 的 HTTP 形态） ----------

    /**
     * @deprecated 自定义形态，<b>标准 MCP 客户端发现不了它</b>。
     *             标准实现是 {@code POST /mcp}（JSON-RPC 2.0，见 {@code McpController}）。
     *             这两个接口保留是因为项目文档与实验脚本引用过它们，
     *             以及它们比 JSON-RPC 更方便手工 curl 看一眼。
     */
    @Deprecated
    @GetMapping("/mcp/tools")
    public Map<String, Object> mcpTools() {
        return Map.of("tools", tools.schemas());
    }

    /** @deprecated 同上，标准实现走 {@code POST /mcp} 的 {@code tools/call}。 */
    @Deprecated
    @PostMapping("/mcp/call")
    public Object mcpCall(@RequestBody Map<String, Object> request) {
        String name = String.valueOf(request.get("name"));
        Object args = request.get("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> argMap = args instanceof Map ? (Map<String, Object>) args : Map.of();
        return tools.invoke(name, argMap);
    }
}
