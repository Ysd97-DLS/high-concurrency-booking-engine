package com.flashpilot.controlplane.l1;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.controlplane.config.ConfigParam;

/**
 * 走 OpenAI 兼容协议的 LLM 客户端。DeepSeek、Moonshot、通义、以及各类本地推理服务
 * （vLLM / Ollama 的 OpenAI 兼容端点）都能直接用，只要改 base-url 和 model。
 *
 * <p><b>为什么强制 tool call 而不是让模型输出 JSON 文本</b>：
 * tool 的参数带 schema，服务端能校验；而「请你只输出 JSON」这种提示词约束
 * 在压力下（尤其是模型想解释一下的时候）非常容易破功。
 *
 * <p>顺带说明成本控制：这个客户端只在指标越界时被调用（事件驱动），
 * 且输入是聚合后的结构化摘要而不是原始时序，所以单次决策的 token 量很小。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private static final String TOOL_CHANGE = "propose_config_change";
    private static final String TOOL_NO_CHANGE = "report_no_change";

    private final FlashPilotProperties.Control.Agent cfg;
    private final ObjectMapper json;
    private final RestClient http;

    public OpenAiCompatibleLlmClient(FlashPilotProperties props, ObjectMapper json) {
        this.cfg = props.control().agent();
        this.json = json;

        // 用 JDK HttpClient 而不是 SimpleClientHttpRequestFactory，
        // 因为后者的 setReadTimeout 是**读超时**（两次收到数据之间的最大间隔），
        // 而不是整体超时。
        //
        // 这个区别在这里是致命的：deepseek-v4-pro 是推理模型，服务端在「思考」期间
        // 会持续吐数据（分块/心跳），于是**每次读都有数据、读超时永不触发**。
        // 实测撞到过一次单次调用耗时 **超过 2 分 40 秒**才返回 ——
        // 而配置写的是 60 秒，看起来完全没生效。
        //
        // JdkClientHttpRequestFactory 把 setReadTimeout 映射到 HttpRequest.timeout()，
        // 那是**整体请求超时**：从发出到响应完成的总时长上限，无论中间有没有数据流动。
        // 这才是控制面需要的语义 —— 一次决策必须有硬性时间上限。
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build());
        factory.setReadTimeout(Duration.ofMillis(cfg.timeoutMs()));

        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .build();
    }

    @Override
    public boolean available() {
        return cfg.enabled() && cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    @Override
    public String model() {
        return cfg.model();
    }

    @Override
    public Optional<AgentDecision> decide(String systemPrompt, String userPayload) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.model());
            body.put("temperature", 0);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPayload)));
            body.put("tools", toolSpecs());
            // 只能用 auto：DeepSeek 不支持 tool_choice="required"（实测 HTTP 400）。
            // 代价是模型会先输出一段推理再调工具，而那段推理就是延迟的主要来源
            // （实测 completion_tokens 1500–2500）。
            body.put("tool_choice", "auto");
            // **必须设上限。** 不设的话推理可以无限长 —— 实测撞到过一次单次调用
            // 超过 2 分 40 秒才返回，而控制面的观察窗口只有 20 秒。
            // 取 4000：实测 flash 用 ~1500、pro 用 ~2400 就能走到 tool call，
            // 留出余量但不给「无限想下去」的空间。
            // 注意不能设太小：auto 模式下推理在 tool call 之前，
            // 截断在推理阶段就完全拿不到结构化决策了。
            body.put("max_tokens", cfg.maxTokens());

            String raw = http.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parse(raw);
        } catch (Exception e) {
            // 调不通就什么都不做，L0 继续兜底。这是设计好的降级，不该让它影响数据面。
            //
            // 但**要说清是什么失败**。原来这里只打 e.toString()，而 Spring 会把读超时
            // 包装成 `RestClientException: Error while extracting response for type
            // [java.lang.String] and content type [application/json]` ——
            // 那句话听起来像「响应格式不对」，而真实原因是 SocketTimeoutException。
            // 实测排查这一个失败花了好几步（对比手工触发成功、看耗时、翻超时配置），
            // 而根因链本来一行就能说明白。
            log.warn("[L1] LLM 调用失败，本轮跳过（L0 规则层继续兜底）：{} ← 根因 {}",
                    e.toString(), rootCauseOf(e));
            return Optional.empty();
        }
    }

    private static String snippet(String s, int max) {
        if (s == null || s.isBlank()) {
            return "(空)";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /** 异常链的最里层，带类名 —— 超时和格式错在最外层看起来是同一个异常。 */
    private static String rootCauseOf(Throwable e) {
        Throwable root = e;
        int guard = 0;
        while (root.getCause() != null && root.getCause() != root && guard++ < 20) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private Optional<AgentDecision> parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        JsonNode root = json.readTree(raw);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode toolCalls = message.path("tool_calls");
        String finish = choice.path("finish_reason").asText("");

        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            // 模型没走 tool call。不去正则抠数字——那样早晚出事。
            String text = message.path("content").asText("");
            // DeepSeek 这类推理模型把思考过程放在 reasoning_content，而不是 content。
            // 少读这个字段的后果实测过：content 和 tool_calls 都是空，
            // 于是归因显示成兜底文案「模型未给出结构化决策」——
            // 那句话听起来像模型不配合，真相是**我们的 max_tokens 把它掐断在思考阶段**。
            String reasoning = message.path("reasoning_content").asText("");
            int ctok = root.path("usage").path("completion_tokens").asInt(-1);

            if ("length".equals(finish)) {
                // 被截断。这是配置问题，不是模型问题，必须说清楚 ——
                // 否则下一个看日志的人会以为「这模型不支持 tool call」，方向就跑偏了。
                log.warn("""
                        [L1] 模型输出被 max_tokens 截断（finish_reason=length，completion_tokens={}），                        没来得及调用工具。推理模型会先输出思考再调工具，                        prompt 越大思考越长 —— 要么加大 max_tokens，要么精简 prompt。                        思考片段：{}""",
                        ctok, snippet(reasoning.isBlank() ? text : reasoning, 300));
                return Optional.of(AgentDecision.noChange(
                        "本轮无提案：模型输出被 max_tokens 截断（用了 " + ctok + " tokens 仍未给出结构化决策）"));
            }

            log.info("[L1] 模型未走 tool call（finish_reason={}, completion_tokens={}），本轮视为不调整。原文：{}",
                    finish, ctok, snippet(text.isBlank() ? reasoning : text, 300));
            String why = !text.isBlank() ? text
                    : !reasoning.isBlank() ? "模型只输出了思考没给结论：" + snippet(reasoning, 200)
                    : "模型返回空内容（finish_reason=" + finish + "）";
            return Optional.of(AgentDecision.noChange(why));
        }

        JsonNode call = toolCalls.get(0).path("function");
        String name = call.path("name").asText("");
        JsonNode args = json.readTree(call.path("arguments").asText("{}"));

        String diagnosis = args.path("diagnosis").asText("");
        if (TOOL_NO_CHANGE.equals(name)) {
            return Optional.of(AgentDecision.noChange(diagnosis));
        }
        if (TOOL_CHANGE.equals(name)) {
            String param = args.path("param").asText(null);
            if (param == null || !args.hasNonNull("value")) {
                return Optional.of(AgentDecision.noChange("提案缺少 param 或 value：" + args));
            }
            return Optional.of(AgentDecision.change(diagnosis, param,
                    args.path("value").asDouble(), args.path("reason").asText("")));
        }
        return Optional.of(AgentDecision.noChange("未知工具 " + name));
    }

    /**
     * 工具定义。参数 schema 里直接把白名单参数枚举进去，
     * 让模型在「能改什么」这一层就受约束，而不是等它提了非法参数再驳回。
     */
    private List<Map<String, Object>> toolSpecs() {
        List<String> paramKeys = new ArrayList<>();
        for (ConfigParam p : ConfigParam.values()) {
            paramKeys.add(p.key());
        }

        Map<String, Object> changeParams = Map.of(
                "type", "object",
                "properties", Map.of(
                        "diagnosis", Map.of("type", "string",
                                "description", "对当前指标异常的归因结论，说明是哪个环节导致的"),
                        "param", Map.of("type", "string", "enum", paramKeys,
                                "description", "要调整的参数"),
                        "value", Map.of("type", "number", "description", "调整后的目标值"),
                        "reason", Map.of("type", "string", "description", "为什么这样调，预期产生什么效果")),
                "required", List.of("diagnosis", "param", "value", "reason"));

        Map<String, Object> noChangeParams = Map.of(
                "type", "object",
                "properties", Map.of(
                        "diagnosis", Map.of("type", "string", "description", "为什么当前不需要调整")),
                "required", List.of("diagnosis"));

        return List.of(
                Map.of("type", "function", "function", Map.of(
                        "name", TOOL_CHANGE,
                        "description", "提出一次参数变更。变更仍会经过白名单、区间钳制、幅度限制和冷却期检查，"
                                + "可能被驳回或被修正后生效。",
                        "parameters", changeParams)),
                Map.of("type", "function", "function", Map.of(
                        "name", TOOL_NO_CHANGE,
                        "description", "当前指标虽然越界但不建议调参（例如刚变更过还在观察窗口内、"
                                + "或异常原因不在可调参数范围内）时调用。",
                        "parameters", noChangeParams)));
    }
}
