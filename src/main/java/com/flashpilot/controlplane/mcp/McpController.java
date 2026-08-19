package com.flashpilot.controlplane.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP 的 Streamable HTTP 传输：单个 {@code POST /mcp} 端点。
 *
 * <p>协议逻辑全在 {@link McpProtocol}（纯函数、可单测），这里只负责搬字节：
 * 收 JSON、交给协议层、把结果写回去。
 *
 * <h2>两个容易忽略的传输层细节</h2>
 * <ul>
 *   <li><b>通知必须回 202 且无 body。</b>JSON-RPC 规定通知没有响应，
 *       而 HTTP 总得回个状态码。回 200 + 空 body 会让部分客户端尝试解析空串而报错。</li>
 *   <li><b>批量请求</b>（body 是数组）要逐条处理并只收集非 null 的响应；
 *       如果一批里全是通知，同样回 202。</li>
 * </ul>
 *
 * <p>不实现 SSE 上行流：本服务端不主动向客户端推送（没有声明 {@code listChanged}，
 * 也没有 sampling / elicitation 需求），所以纯 POST 请求-响应就够了。
 * <b>声明了却不实现的能力比不声明更糟</b>，这也是 {@code initialize} 里
 * 只声明 {@code tools} 的原因。
 */
@RestController
@RequestMapping
public class McpController {

    private final McpProtocol protocol;

    public McpController(McpToolGateway gateway, ObjectMapper json,
                         @Value("${spring.application.name:flashpilot}") String appName) {
        this.protocol = new McpProtocol(gateway, json, appName + "-control-plane", "0.1.0");
    }

    @PostMapping(path = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody(required = false) Object body,
                                    @RequestHeader(value = "MCP-Protocol-Version",
                                                   required = false) String clientVersion) {
        // MCP-Protocol-Version 头是客户端在 initialize 之后每个请求都该带的。
        // 这里刻意不做强校验：拒绝一个没带头的客户端，除了增加接入摩擦没有任何安全收益 ——
        // 真正的版本约束在 initialize 的协商里已经完成了。
        if (body instanceof List<?> batch) {
            List<Map<String, Object>> out = batch.stream()
                    .map(McpController::asMap)
                    .map(protocol::handle)
                    .filter(r -> r != null)
                    .toList();
            return out.isEmpty()
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.ok(out);
        }

        Map<String, Object> response = protocol.handle(asMap(body));
        return response == null
                ? ResponseEntity.status(HttpStatus.ACCEPTED).build()   // 通知：无响应体
                : ResponseEntity.ok(response);
    }

    /**
     * body 解析失败 → JSON-RPC {@code -32700 Parse error}。
     *
     * <p><b>这个处理器不能省。</b>{@link McpProtocol} 里写了 -32700 的分支，但它永远走不到：
     * Jackson 在进入协议层<b>之前</b>就失败了，Spring 会返回自己的默认错误体
     * （{@code {"timestamp":...,"status":400,"error":"Bad Request"}}）——
     * 那不是一个 JSON-RPC 响应，严格的 MCP 客户端会在解析响应时二次失败，
     * 拿到的错误信息和真正的原因完全无关。
     *
     * <p>这个缺口是端到端测试<b>意外</b>暴露的：我在 curl 的参数里写了中文，
     * Git Bash 按 GBK 送出去，于是 body 不是合法 UTF-8。
     * 手工测试如果只发合规请求，永远发现不了它 ——
     * <b>协议实现的合规性缺口，几乎都藏在「客户端发了不该发的东西」这一侧。</b>
     *
     * <p>按 JSON-RPC 规范，解析失败时 {@code id} 必须为 {@code null}：
     * 请求都没解析出来，自然不知道它的 id 是什么。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnparseable(HttpMessageNotReadableException e) {
        Map<String, Object> err = new java.util.LinkedHashMap<>();
        err.put("code", -32700);
        err.put("message", "Parse error");
        err.put("data", Map.of("detail", String.valueOf(e.getMostSpecificCause().getMessage())));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", err);
        // 仍然回 200：JSON-RPC 的错误是<b>协议层</b>的错误，用响应体表达，
        // 和本项目数据面「业务错误走 HTTP 200 + code」是同一条纪律。
        // 回 400 会让客户端的 HTTP 层先抛，拿不到这个结构化的错误原因。
        return ResponseEntity.ok(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
