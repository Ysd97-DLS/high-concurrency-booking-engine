package com.flashpilot.controlplane.l1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.flashpilot.controlplane.MetricsCollector;
import com.flashpilot.controlplane.config.ConfigAuditRepository;
import com.flashpilot.controlplane.config.ConfigParam;
import com.flashpilot.controlplane.config.HotConfigService;
import com.flashpilot.controlplane.guard.ChangeProposal;
import com.flashpilot.controlplane.guard.GuardRail;
import com.flashpilot.dataplane.stock.StockRedisRepository;
import com.flashpilot.verify.ExperimentContext;

/**
 * 控制面的工具层：把「能对系统做什么」收敛成一组带 schema 的工具。
 *
 * <p><b>读写分权限</b>是这里最重要的设计：只读工具随便调，写工具（改配置、回滚）
 * 全部强制经过 {@link GuardRail}。即使调用方是个完全不受控的客户端，它能造成的最大破坏
 * 也被限制在白名单参数的合法区间内。
 *
 * <p><b>与 MCP 的关系</b>：这一层就是 MCP Server 要包装的东西——{@link #schemas()} 直接对应
 * MCP 的 {@code tools/list}，{@link #invoke} 对应 {@code tools/call}。
 * 当前通过 HTTP 暴露（见 {@code /mcp/tools} 与 {@code /mcp/call}），
 * 换成标准 MCP 的 stdio / SSE 传输是机械工作，属于 W5 的任务。
 */
@Component
public class AgentTools {

    private final MetricsCollector collector;
    private final HotConfigService hotConfig;
    private final GuardRail guard;
    private final ConfigAuditRepository audit;
    private final StockRedisRepository stockRedis;
    private final ExperimentContext experiment;

    public AgentTools(MetricsCollector collector, HotConfigService hotConfig, GuardRail guard,
                      ConfigAuditRepository audit, StockRedisRepository stockRedis,
                      ExperimentContext experiment) {
        this.collector = collector;
        this.hotConfig = hotConfig;
        this.guard = guard;
        this.audit = audit;
        this.stockRedis = stockRedis;
        this.experiment = experiment;
    }

    /** MCP {@code tools/list} 的等价物。 */
    public List<Map<String, Object>> schemas() {
        List<String> paramKeys = new ArrayList<>();
        for (ConfigParam p : ConfigParam.values()) {
            paramKeys.add(p.key());
        }
        Map<String, Object> empty = Map.of("type", "object", "properties", Map.of());

        return List.of(
                tool("get_metrics", "读取当前指标快照：QPS、P99、误拒率、桶倾斜度、号段命中率、消费积压等。",
                        empty, true),
                tool("get_config", "读取当前生效的热参数及其允许区间。", empty, true),
                tool("get_stock_detail", "读取库存分布：每个桶的余量、各实例本地持有量、全局剩余。",
                        empty, true),
                tool("get_recent_changes", "读取最近若干次生效的参数变更及其理由。",
                        Map.of("type", "object", "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "返回条数，默认 5")))
                        , true),
                tool("propose_config_change",
                        "提出一次参数变更。会经过白名单、区间钳制、幅度限制、冷却期检查，可能被修正或驳回。",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "param", Map.of("type", "string", "enum", paramKeys),
                                        "value", Map.of("type", "number"),
                                        "reason", Map.of("type", "string"),
                                        "dryRun", Map.of("type", "boolean",
                                                "description", "true 时只校验不写入")),
                                "required", List.of("param", "value", "reason")),
                        false),
                tool("rollback_last_change", "把最后一次生效的变更改回去。", empty, false));
    }

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> schema, boolean readOnly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", schema);
        m.put("readOnly", readOnly);
        return m;
    }

    /** MCP {@code tools/call} 的等价物。 */
    public Object invoke(String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return switch (name) {
            case "get_metrics" -> collector.latest().toAgentSummary();
            case "get_config" -> Map.of(
                    "version", hotConfig.version(),
                    "values", hotConfig.snapshot(),
                    "bounds", bounds());
            case "get_stock_detail" -> stockDetail();
            case "get_recent_changes" -> audit.recentAccepted(intArg(safeArgs, "limit", 5));
            case "propose_config_change" -> proposeChange(safeArgs);
            case "rollback_last_change" -> Map.of("result",
                    hotConfig.rollbackLast().orElse("没有可回滚的变更"));
            default -> Map.of("error", "未知工具：" + name);
        };
    }

    private Map<String, Object> proposeChange(Map<String, Object> args) {
        Object param = args.get("param");
        Object value = args.get("value");
        if (param == null || !(value instanceof Number n)) {
            return Map.of("error", "param 和 value 是必填项，且 value 必须是数字");
        }
        boolean dryRun = Boolean.TRUE.equals(args.get("dryRun"));
        String reason = args.get("reason") == null ? "（未提供理由）" : String.valueOf(args.get("reason"));
        GuardRail.GuardResult r = guard.submit(
                ChangeProposal.agent(String.valueOf(param), n.doubleValue(), reason), dryRun);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", r.accepted());
        out.put("wouldApply", r.wouldApply());
        out.put("param", r.param());
        out.put("appliedValue", r.appliedValue());
        out.put("guardNote", r.note());
        out.put("configVersion", r.version());
        return out;
    }

    private Map<String, Object> bounds() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ConfigParam p : ConfigParam.values()) {
            m.put(p.key(), Map.of("min", p.min(), "max", p.max()));
        }
        return m;
    }

    private Map<String, Object> stockDetail() {
        long poolId = experiment.poolId();
        int active = hotConfig.getInt(ConfigParam.ACTIVE_BUCKETS);
        StockRedisRepository.Stats stats = stockRedis.stats(poolId);

        List<Integer> activeBuckets = new ArrayList<>();
        for (int i = 0; i < Math.min(active, stats.buckets().length); i++) {
            activeBuckets.add(stats.buckets()[i]);
        }
        int spillover = 0;
        for (int i = active; i < stats.buckets().length; i++) {
            spillover += stats.buckets()[i];
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolId", poolId);
        m.put("activeBucketValues", activeBuckets);
        m.put("stockInInactiveBuckets", spillover);
        m.put("bucketSum", stats.bucketSum());
        m.put("heldByInstances", stats.leaseHeld());
        m.put("instanceCount", stats.instances());
        m.put("globalRemaining", stats.total());
        m.put("skew", stats.skew(active));
        return m;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }
}
