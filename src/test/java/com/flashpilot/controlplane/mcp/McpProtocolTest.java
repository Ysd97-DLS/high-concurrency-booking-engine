package com.flashpilot.controlplane.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 协议层测试。
 *
 * <p>协议实现的价值全在<b>互操作性</b>：别人的客户端能不能接上。而互操作性是一堆
 * 「不这么做就连不上」的细节——通知不能有响应、未知工具和执行失败走不同通道、
 * 版本不匹配要降级而不是报错。这些细节<b>手工 curl 一遍是测不出来的</b>：
 * 你会照着自己的实现去构造请求，于是永远走在自己已经实现的那条路上。
 */
class McpProtocolTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 假工具网关：两个只读工具，一个会抛异常。 */
    private static class FakeGateway implements McpToolGateway {
        @Override
        public List<Map<String, Object>> listTools() {
            return List.of(
                    Map.of("name", "get_metrics", "description", "读指标",
                            "inputSchema", Map.of("type", "object", "properties", Map.of()),
                            "readOnly", true),
                    Map.of("name", "propose_config_change", "description", "改参数",
                            "inputSchema", Map.of("type", "object", "properties", Map.of()),
                            "readOnly", false),
                    Map.of("name", "boom", "description", "总是失败",
                            "inputSchema", Map.of("type", "object", "properties", Map.of()),
                            "readOnly", true));
        }

        @Override
        public boolean hasTool(String name) {
            return listTools().stream().anyMatch(t -> t.get("name").equals(name));
        }

        @Override
        public Object callTool(String name, Map<String, Object> args) {
            if ("boom".equals(name)) {
                throw new IllegalStateException("依赖不可用");
            }
            return Map.of("echo", name, "args", args);
        }
    }

    private McpProtocol protocol() {
        return new McpProtocol(new FakeGateway(), json, "test-server", "9.9.9");
    }

    private static Map<String, Object> req(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) {
            m.put("id", id);
        }
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    // ---------- initialize / 版本协商 ----------

    @Test
    @DisplayName("initialize 回同一个版本（当服务端支持它）")
    void initializeEchoesSupportedVersion() {
        Map<String, Object> r = protocol().handle(
                req(1, "initialize", Map.of("protocolVersion", "2024-11-05")));
        Map<String, Object> result = sub(r, "result");
        assertEquals("2024-11-05", result.get("protocolVersion"));
    }

    @Test
    @DisplayName("版本不支持时降级到服务端最新版，【而不是报错】")
    void unsupportedVersionDegradesInsteadOfFailing() {
        // 规范：服务端不支持请求的版本时，MUST 回一个自己支持的版本，
        // 由客户端决定要不要断开。写成 -32602 会让任何版本略旧的客户端直接连不上。
        Map<String, Object> r = protocol().handle(
                req(1, "initialize", Map.of("protocolVersion", "1999-01-01")));
        assertNull(r.get("error"), "版本不匹配被当成错误了：" + r.get("error"));
        assertEquals(McpProtocol.SUPPORTED_VERSIONS.get(0), sub(r, "result").get("protocolVersion"));
    }

    @Test
    @DisplayName("完全没传 protocolVersion 也要能协商成功")
    void missingVersionStillNegotiates() {
        Map<String, Object> r = protocol().handle(req(1, "initialize", Map.of()));
        assertNull(r.get("error"));
        assertNotNull(sub(r, "result").get("protocolVersion"));
    }

    @Test
    @DisplayName("只声明真正实现了的能力：有 tools，没有 prompts/resources")
    void declaresOnlyImplementedCapabilities() {
        // 声明了却没实现，客户端会去调 prompts/list 然后拿到 -32601。
        // 能力声明是一份承诺，不是许愿。
        Map<String, Object> caps = sub(sub(protocol().handle(req(1, "initialize", Map.of())), "result"),
                "capabilities");
        assertTrue(caps.containsKey("tools"));
        assertFalse(caps.containsKey("prompts"), "声明了未实现的 prompts 能力");
        assertFalse(caps.containsKey("resources"), "声明了未实现的 resources 能力");
    }

    @Test
    @DisplayName("serverInfo 带名字和版本")
    void reportsServerInfo() {
        Map<String, Object> info = sub(sub(protocol().handle(req(1, "initialize", Map.of())), "result"),
                "serverInfo");
        assertEquals("test-server", info.get("name"));
        assertEquals("9.9.9", info.get("version"));
    }

    // ---------- 通知 ----------

    @ParameterizedTest
    @ValueSource(strings = {"notifications/initialized", "notifications/cancelled"})
    @DisplayName("通知（无 id）绝不能有响应")
    void notificationsGetNoResponse(String method) {
        // 回一个响应，严格的客户端会因为「收到了没有对应请求的 id」而报错或断开。
        assertNull(protocol().handle(req(null, method, null)),
                method + " 返回了响应");
    }

    @Test
    @DisplayName("id 显式为 null 也算通知")
    void explicitNullIdIsNotification() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", null);
        m.put("method", "notifications/initialized");
        assertNull(protocol().handle(m));
    }

    @Test
    @DisplayName("结构非法的通知也不回响应——不能因为它错就破坏通知语义")
    void malformedNotificationStillSilent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");   // 没有 id、没有 method
        assertNull(protocol().handle(m));
    }

    // ---------- tools/list ----------

    @Test
    @DisplayName("tools/list 返回 MCP 形状：name / description / inputSchema")
    void toolsListShape() {
        Map<String, Object> result = sub(protocol().handle(req(2, "tools/list", null)), "result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("tools");
        assertEquals(3, list.size());
        for (Map<String, Object> t : list) {
            assertNotNull(t.get("name"));
            assertNotNull(t.get("description"));
            assertNotNull(t.get("inputSchema"), "缺 inputSchema，客户端无法生成调用参数");
            assertFalse(t.containsKey("readOnly"), "内部字段 readOnly 泄漏到了协议输出里");
        }
    }

    @Test
    @DisplayName("readOnly 映射成标准的 annotations.readOnlyHint")
    void readOnlyBecomesAnnotation() {
        // 这个提示让宿主可以对只读工具免确认、对会改参数的工具弹确认框——
        // 也就是 MCP 规范里「人在回路」能真正落地的前提。
        Map<String, Object> result = sub(protocol().handle(req(2, "tools/list", null)), "result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("tools");

        Map<String, Object> ro = list.stream()
                .filter(t -> "get_metrics".equals(t.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, sub(ro, "annotations").get("readOnlyHint"));

        Map<String, Object> rw = list.stream()
                .filter(t -> "propose_config_change".equals(t.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, sub(rw, "annotations").get("readOnlyHint"),
                "会改生产参数的工具被标成了只读");
        assertEquals(Boolean.TRUE, sub(rw, "annotations").get("destructiveHint"));
    }

    // ---------- tools/call：两条错误通道 ----------

    @Test
    @DisplayName("正常调用返回 content 数组 + isError=false")
    void successfulCall() {
        Map<String, Object> r = protocol().handle(req(3, "tools/call",
                Map.of("name", "get_metrics", "arguments", Map.of("limit", 5))));
        Map<String, Object> result = sub(r, "result");
        assertEquals(Boolean.FALSE, result.get("isError"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("text", content.get(0).get("type"));
        assertTrue(String.valueOf(content.get(0).get("text")).contains("get_metrics"));
    }

    @Test
    @DisplayName("【未知工具】是协议错误 -32602，不是 isError 结果")
    void unknownToolIsProtocolError() {
        // 报成 isError 结果，模型会以为工具存在而反复重试。
        Map<String, Object> r = protocol().handle(req(3, "tools/call",
                Map.of("name", "no_such_tool", "arguments", Map.of())));
        assertNull(r.get("result"), "未知工具返回了 result");
        assertEquals(McpProtocol.INVALID_PARAMS, sub(r, "error").get("code"));
        assertNotNull(sub(r, "error").get("data"), "应把可用工具列表放进 data 帮助调用方纠正");
    }

    @Test
    @DisplayName("【工具执行失败】是 isError 结果，不是协议错误")
    void toolFailureIsResultNotProtocolError() {
        // 报成 -32603，宿主通常会认定这个工具坏了而不再使用它，
        // 而实际上往往只是这次的参数或依赖状态不合适。
        Map<String, Object> r = protocol().handle(req(4, "tools/call",
                Map.of("name", "boom", "arguments", Map.of())));
        assertNull(r.get("error"), "执行失败被报成了协议错误：" + r.get("error"));
        Map<String, Object> result = sub(r, "result");
        assertEquals(Boolean.TRUE, result.get("isError"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue(String.valueOf(content.get(0).get("text")).contains("依赖不可用"),
                "失败原因没有传给调用方，它无从判断该改什么");
    }

    @Test
    @DisplayName("tools/call 缺 name 是 -32602")
    void missingToolNameIsInvalidParams() {
        Map<String, Object> r = protocol().handle(req(5, "tools/call", Map.of("arguments", Map.of())));
        assertEquals(McpProtocol.INVALID_PARAMS, sub(r, "error").get("code"));
    }

    @Test
    @DisplayName("arguments 缺失时按空参数处理，不该报错")
    void missingArgumentsDefaultsToEmpty() {
        // 无参工具（get_metrics 的 schema 是空 object）被调用时客户端常常省略 arguments。
        Map<String, Object> r = protocol().handle(req(6, "tools/call", Map.of("name", "get_metrics")));
        assertNull(r.get("error"));
        assertEquals(Boolean.FALSE, sub(r, "result").get("isError"));
    }

    // ---------- 其它方法与错误码 ----------

    @Test
    @DisplayName("ping 回空结果")
    void pingReturnsEmptyResult() {
        Map<String, Object> r = protocol().handle(req(7, "ping", null));
        assertNotNull(r.get("result"));
        assertNull(r.get("error"));
    }

    @Test
    @DisplayName("未实现的方法回 -32601")
    void unknownMethodIsMethodNotFound() {
        for (String m : List.of("prompts/list", "resources/list", "completion/complete")) {
            Map<String, Object> r = protocol().handle(req(8, m, null));
            assertEquals(McpProtocol.METHOD_NOT_FOUND, sub(r, "error").get("code"), m);
        }
    }

    @Test
    @DisplayName("缺 method 的请求（有 id）回 -32600")
    void missingMethodIsInvalidRequest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", 9);
        assertEquals(McpProtocol.INVALID_REQUEST, sub(protocol().handle(m), "error").get("code"));
    }

    @Test
    @DisplayName("null 请求回 -32700")
    void nullRequestIsParseError() {
        assertEquals(McpProtocol.PARSE_ERROR, sub(protocol().handle(null), "error").get("code"));
    }

    @Test
    @DisplayName("响应必须回显原 id，且 jsonrpc 恒为 2.0")
    void echoesIdAndVersion() {
        for (Object id : List.of(42, "abc-123")) {
            Map<String, Object> r = protocol().handle(req(id, "ping", null));
            assertEquals(id, r.get("id"), "id 没有回显，客户端无法把响应配对到请求");
            assertEquals("2.0", r.get("jsonrpc"));
        }
    }

    @Test
    @DisplayName("协议层不吞异常成功：网关抛出的非工具异常仍走 isError 而不是崩掉")
    void gatewayExceptionDoesNotEscape() {
        McpProtocol p = new McpProtocol(new McpToolGateway() {
            @Override public List<Map<String, Object>> listTools() {
                return List.of(Map.of("name", "x", "description", "d",
                        "inputSchema", Map.of("type", "object")));
            }
            @Override public boolean hasTool(String name) { return true; }
            @Override public Object callTool(String name, Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        }, json, "s", "v");
        Map<String, Object> r = p.handle(req(1, "tools/call", Map.of("name", "x")));
        assertEquals(Boolean.TRUE, sub(r, "result").get("isError"));
    }
}
