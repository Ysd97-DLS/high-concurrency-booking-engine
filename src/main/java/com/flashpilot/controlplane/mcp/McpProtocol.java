package com.flashpilot.controlplane.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP（Model Context Protocol）的协议层：JSON-RPC 2.0 分发。
 *
 * <h2>为什么要做成标准协议而不是自定义两个 HTTP 接口</h2>
 *
 * 原来控制面暴露的是 {@code GET /mcp/tools} 和 {@code POST /mcp/call} —— 语义对了，
 * 但那是<b>自己定的两个接口</b>，任何标准 MCP 客户端都发现不了它。
 * 换成标准协议之后，这套控制面工具可以被任意 MCP 宿主直接接入，
 * 而不是只能被本项目的 {@code AgentLoop} 调用。
 * <b>「能被别人的客户端接入」和「我自己能调通」是两个量级的完成度。</b>
 *
 * <h2>两条错误通道，这是最容易做错的地方</h2>
 *
 * MCP 规范把错误分成两类，含义完全不同：
 * <ul>
 *   <li><b>协议错误</b> → JSON-RPC {@code error} 对象。用于「未知方法」「未知工具」
 *       「参数结构非法」。含义是<i>这次调用本身不成立</i>。</li>
 *   <li><b>工具执行错误</b> → 正常的 {@code result}，但带 {@code isError: true}。
 *       用于「工具跑了但失败了」：依赖不可用、业务规则拒绝、参数值不合理。</li>
 * </ul>
 *
 * <p>混成一条的后果很具体：模型分不清「我该换个工具」和「我该换个参数再试」。
 * 把执行失败报成协议错误，宿主通常会直接放弃这个工具；
 * 把未知工具报成 {@code isError} 结果，模型会以为工具存在、反复重试。
 *
 * <p>值得一提的是这和本项目<b>数据面早就定下的约定是同一个形状</b>：
 * 业务错误走 HTTP 200 + 业务码，只有传输层错误才是非 200。
 * 「售罄」不是异常，「工具执行失败」也不是协议错误 ——
 * <b>正常的失败和「你的请求根本不成立」必须分开表达。</b>
 *
 * <h2>纯函数</h2>
 *
 * 这个类不碰网络、不碰 Spring、不持有会话状态：输入一个已解析的 JSON-RPC 请求 Map，
 * 输出一个响应 Map（通知返回 {@code null}）。传输层（HTTP / stdio）只负责搬字节。
 */
public class McpProtocol {

    /** 本服务端实现的协议版本，最新的排前面。版本协商时优先回第一个。 */
    static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2024-11-05");

    /** JSON-RPC 2.0 标准错误码。 */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private final McpToolGateway tools;
    private final ObjectMapper json;
    private final String serverName;
    private final String serverVersion;

    public McpProtocol(McpToolGateway tools, ObjectMapper json,
                       String serverName, String serverVersion) {
        this.tools = tools;
        this.json = json;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /**
     * 处理一条 JSON-RPC 消息。
     *
     * @return 响应 Map；<b>如果输入是通知（没有 {@code id}），返回 {@code null}</b> ——
     *         JSON-RPC 规定通知不得有响应。这一条很容易漏：给
     *         {@code notifications/initialized} 回一个响应，严格的客户端会因为
     *         「收到了一个没有对应请求的 id」而报错或断开。
     */
    public Map<String, Object> handle(Map<String, Object> request) {
        if (request == null) {
            return error(null, PARSE_ERROR, "请求为空", null);
        }
        Object id = request.get("id");
        boolean isNotification = !request.containsKey("id") || id == null;

        Object methodObj = request.get("method");
        if (!(methodObj instanceof String method) || method.isBlank()) {
            return isNotification ? null : error(id, INVALID_REQUEST, "缺少 method", null);
        }

        // 通知：处理完不回响应。目前只有 initialized / cancelled 这类，无副作用。
        if (isNotification) {
            return null;
        }

        Map<String, Object> params = asMap(request.get("params"));

        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize(params));
                case "ping" -> ok(id, Map.of());           // 规范要求回空结果
                case "tools/list" -> ok(id, Map.of("tools", mcpToolList()));
                case "tools/call" -> toolsCall(id, params);
                default -> error(id, METHOD_NOT_FOUND, "未实现的方法：" + method, null);
            };
        } catch (RuntimeException e) {
            // 协议层自己出错才走 -32603。工具执行失败不走这里 —— 见 toolsCall。
            return error(id, INTERNAL_ERROR, "服务端内部错误：" + e, null);
        }
    }

    /**
     * 版本协商。
     *
     * <p>规范：客户端请求一个版本，服务端支持就<b>回同一个</b>；不支持就回自己支持的
     * （应当是最新的），<b>而不是报错</b> —— 让客户端自己决定要不要断开。
     * 写成「不匹配就报 -32602」是个常见误读：那样任何版本略旧的客户端都直接连不上，
     * 而协议本来是设计成可以降级共存的。
     */
    private Map<String, Object> initialize(Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        String agreed = (requested instanceof String v && SUPPORTED_VERSIONS.contains(v))
                ? v
                : SUPPORTED_VERSIONS.get(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", agreed);
        // 只声明真正实现了的能力。声明了 prompts/resources 却没实现，
        // 客户端会去调 prompts/list 然后拿到 -32601 —— 能力声明就是一份承诺。
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", serverName, "version", serverVersion));
        result.put("instructions",
                "这是 FlashPilot 秒杀系统的控制面。工具分两类：get_* 只读，"
                + "propose_config_change / rollback_last_change 会改动生产参数。"
                + "所有变更都会经过护栏（白名单、区间钳制、幅度限制、冷却期），"
                + "可能被修正或驳回 —— 务必读返回里的 note，不要假设提案原样生效。");
        return result;
    }

    /** 把内部工具清单转成 MCP 的 tool 对象。 */
    private List<Map<String, Object>> mcpToolList() {
        return tools.listTools().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.get("name"));
            m.put("description", t.get("description"));
            m.put("inputSchema", t.get("inputSchema"));
            // 内部用的是 readOnly 布尔，MCP 的标准位置是 annotations.readOnlyHint。
            // 这个提示让宿主可以对只读工具免去人工确认，而对会改参数的工具弹确认框 ——
            // 也就是「人在回路」能落地的前提。
            Object readOnly = t.get("readOnly");
            if (readOnly instanceof Boolean b) {
                m.put("annotations", Map.of("readOnlyHint", b, "destructiveHint", !b));
            }
            return m;
        }).toList();
    }

    private Map<String, Object> toolsCall(Object id, Map<String, Object> params) {
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            return error(id, INVALID_PARAMS, "tools/call 缺少 name", null);
        }
        // 未知工具是<b>协议错误</b>，不是执行失败。见类注释里的两条通道。
        if (!tools.hasTool(name)) {
            return error(id, INVALID_PARAMS, "未知工具：" + name,
                    Map.of("available", tools.listTools().stream().map(t -> t.get("name")).toList()));
        }

        Map<String, Object> args = asMap(params.get("arguments"));
        try {
            Object out = tools.callTool(name, args);
            return ok(id, content(text(out), false));
        } catch (RuntimeException e) {
            // 工具跑了但失败了 → 正常 result + isError:true。
            // 报成 -32603 的话宿主通常会认为这个工具坏了而不再使用它，
            // 而实际上往往只是这次参数不合适。
            return ok(id, content("工具执行失败：" + e.getMessage(), true));
        }
    }

    private Map<String, Object> content(String text, boolean isError) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", List.of(Map.of("type", "text", "text", text)));
        r.put("isError", isError);
        return r;
    }

    /**
     * 工具返回值转文本。
     *
     * <p>MCP 的 {@code content} 是给模型读的，所以统一序列化成 JSON 文本 ——
     * 结构化数据用 JSON 字符串比用自然语言描述更省 token 也更不容易被读错。
     */
    private String text(Object value) {
        if (value instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) {
            err.put("data", data);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);           // 规范：解析失败时 id 为 null，其余回原 id
        m.put("error", err);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** 供测试与文档使用。 */
    public static Set<String> methods() {
        return Set.of("initialize", "ping", "tools/list", "tools/call");
    }
}
