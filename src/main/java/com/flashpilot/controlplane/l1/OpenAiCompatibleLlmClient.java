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

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
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
            body.put("tool_choice", "auto");

            String raw = http.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parse(raw);
        } catch (Exception e) {
            // 调不通就什么都不做，L0 继续兜底。这是设计好的降级，不该让它影响数据面。
            log.warn("[L1] LLM 调用失败，本轮跳过（L0 规则层继续兜底）：{}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<AgentDecision> parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        JsonNode root = json.readTree(raw);
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");

        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            // 模型没走 tool call，只回了文本。不去正则抠数字——那样早晚出事。
            String text = message.path("content").asText("");
            log.info("[L1] 模型未走 tool call，本轮视为不调整。原文：{}",
                    text.length() > 200 ? text.substring(0, 200) + "…" : text);
            return Optional.of(AgentDecision.noChange(text.isBlank() ? "模型未给出结构化决策" : text));
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
