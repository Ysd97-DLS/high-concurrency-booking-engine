package com.flashpilot.controlplane.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON-RPC 批量请求的行为。
 *
 * <p>单独一个类是因为批量的规则和单条不同，而这些不同点全是「不这么做就不互操作」的：
 * 批里混着请求和通知时，响应数组<b>只能包含请求的响应</b>；
 * 一批全是通知时不能回空数组，而应当无响应体。
 */
class McpBatchTest {

    private final ObjectMapper json = new ObjectMapper();

    private McpProtocol protocol() {
        return new McpProtocol(new McpToolGateway() {
            @Override public List<Map<String, Object>> listTools() {
                return List.of(Map.of("name", "t", "description", "d",
                        "inputSchema", Map.of("type", "object"), "readOnly", true));
            }
            @Override public boolean hasTool(String name) { return "t".equals(name); }
            @Override public Object callTool(String name, Map<String, Object> args) {
                return Map.of("ok", true);
            }
        }, json, "s", "v");
    }

    private static Map<String, Object> req(Object id, String method) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) {
            m.put("id", id);
        }
        m.put("method", method);
        return m;
    }

    @Test
    @DisplayName("批里混着请求和通知时，通知不产生响应项")
    void notificationsAreOmittedFromBatchResponse() {
        // 如果给通知也塞一项（哪怕是 null），客户端按 id 配对时会拿到一个
        // 对不上任何请求的条目 —— 严格实现会报错。
        List<Map<String, Object>> batch = List.of(
                req(1, "ping"),
                req(null, "notifications/initialized"),
                req(2, "tools/list"));

        List<Map<String, Object>> out = batch.stream()
                .map(protocol()::handle)
                .filter(r -> r != null)
                .toList();

        assertEquals(2, out.size(), "通知产生了响应项");
        assertEquals(1, out.get(0).get("id"));
        assertEquals(2, out.get(1).get("id"));
    }

    @Test
    @DisplayName("一批全是通知时，结果集为空（传输层应回 202 无 body）")
    void allNotificationsYieldEmptyBatch() {
        List<Map<String, Object>> batch = List.of(
                req(null, "notifications/initialized"),
                req(null, "notifications/cancelled"));
        assertTrue(batch.stream().map(protocol()::handle).filter(r -> r != null).toList().isEmpty());
    }

    @Test
    @DisplayName("批里某一条出错不影响其它条")
    void oneFailureDoesNotAffectOthers() {
        McpProtocol p = protocol();
        assertNull(p.handle(req(null, "notifications/initialized")));

        Map<String, Object> bad = p.handle(req(1, "no/such/method"));
        Map<String, Object> good = p.handle(req(2, "ping"));

        assertEquals(McpProtocol.METHOD_NOT_FOUND,
                ((Map<?, ?>) bad.get("error")).get("code"));
        assertNull(good.get("error"), "同批里的正常请求被带累了");
    }
}
