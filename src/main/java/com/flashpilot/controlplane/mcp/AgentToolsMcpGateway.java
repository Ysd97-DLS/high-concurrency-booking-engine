package com.flashpilot.controlplane.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.flashpilot.controlplane.l1.AgentTools;

/**
 * 把已有的 {@link AgentTools} 适配成 MCP 协议层需要的网关。
 *
 * <p>这一层只做两件事，但都不能省：
 * <ol>
 *   <li><b>提供「工具是否存在」的查询。</b>{@code AgentTools.invoke} 对未知工具返回
 *       {@code {"error": "未知工具：x"}} —— 那是一个<i>正常返回值</i>，
 *       协议层无法据此判断该回协议错误还是执行失败。所以在这里先查一次。</li>
 *   <li><b>把返回值里的失败语义翻译成异常。</b>协议层用「抛异常 = 执行失败」这个约定，
 *       比让它去猜返回 Map 里有没有 error 键要可靠得多。</li>
 * </ol>
 *
 * <p>不直接改 {@code AgentTools} 是因为它同时被 {@code AgentLoop} 用着，
 * 而那条路径对返回值形状有自己的假设。<b>适配器比改动被多方依赖的接口安全。</b>
 */
@Component
public class AgentToolsMcpGateway implements McpToolGateway {

    private final AgentTools tools;

    public AgentToolsMcpGateway(AgentTools tools) {
        this.tools = tools;
    }

    @Override
    public List<Map<String, Object>> listTools() {
        return tools.schemas();
    }

    @Override
    public boolean hasTool(String name) {
        return names().contains(name);
    }

    private Set<String> names() {
        return tools.schemas().stream()
                .map(t -> String.valueOf(t.get("name")))
                .collect(Collectors.toSet());
    }

    @Override
    public Object callTool(String name, Map<String, Object> args) {
        Object out = tools.invoke(name, args);
        // AgentTools 用返回值表达失败，协议层用异常表达失败，这里做翻译。
        if (out instanceof Map<?, ?> m && m.containsKey("error")) {
            throw new IllegalStateException(String.valueOf(m.get("error")));
        }
        return out;
    }
}
