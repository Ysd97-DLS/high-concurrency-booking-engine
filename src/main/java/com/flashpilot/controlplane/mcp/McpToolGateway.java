package com.flashpilot.controlplane.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议层看到的「工具」抽象。
 *
 * <p>存在的理由是让 {@link McpProtocol} 可以在<b>没有 Spring、没有 Redis、没有 MySQL</b>
 * 的情况下被单测。协议层的价值全在那些边界上——未知方法回什么错误码、
 * 通知要不要回响应、版本协商不匹配时该报错还是降级、工具执行失败走哪条错误通道——
 * 而这些边界一旦要靠起整个应用来验证，实际上就不会被验证。
 *
 * <p>实现见 {@link AgentToolsMcpGateway}，它把已有的 {@code AgentTools} 适配过来。
 */
public interface McpToolGateway {

    /** 工具清单，每项必须含 {@code name} / {@code description} / {@code inputSchema}。 */
    List<Map<String, Object>> listTools();

    /**
     * 工具是否存在。
     *
     * <p><b>必须单独有这个方法。</b>MCP 规范把「工具不存在」列为<b>协议错误</b>
     * （JSON-RPC {@code -32602}），而「工具执行失败」是<b>结果</b>（{@code isError: true}）。
     * 两者对调用方的含义完全不同：前者说明它该换一个工具，后者说明它该换参数或重试。
     * 没有这个方法就只能靠 catch 异常去猜，两条通道会被混成一条。
     */
    boolean hasTool(String name);

    /** 执行工具。抛异常表示执行失败（会被转成 {@code isError: true} 的结果，不是协议错误）。 */
    Object callTool(String name, Map<String, Object> args);
}
