package com.flashpilot.config.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.flashpilot.clinic.auth.PatientIdentity;

/**
 * 把认证类异常翻成规矩的 HTTP 状态码。
 *
 * <p><b>为什么需要它</b>：没有这个 advice，{@code PatientIdentity.require()} 抛出的异常
 * 会被 Spring 当成未捕获错误 → <b>500 Internal Server Error</b>。后果有两层：
 *
 * <ul>
 *   <li>前端没法区分「没登录，去拿令牌」和「服务端挂了，重试」——
 *       而这两种情况的正确处理完全相反；</li>
 *   <li>500 会打印完整堆栈到日志，压测时几万个未带令牌的请求能把磁盘写满，
 *       同时把真正的错误埋掉。</li>
 * </ul>
 *
 * <p>另外 500 会被压测工具统计成 error，而这个项目的判据里
 * 「售罄」「限流」都刻意用 200 + 业务码返回，就是为了不污染错误率 ——
 * 认证失败同理，它是<b>预期内</b>的客户端问题，必须是 4xx。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PatientIdentity.UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> unauthenticated(
            PatientIdentity.UnauthenticatedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "UNAUTHENTICATED");
        body.put("message", e.getMessage());
        body.put("header", PatientIdentity.HEADER);
        // 401 而不是 403：403 的语义是「知道你是谁，但你不能做这件事」，
        // 这里是「不知道你是谁」。前端据此决定是去登录还是提示无权限。
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * 客户端输入不合法 → 400，而不是 Spring 默认的 500 + 完整堆栈。
     *
     * <p>没有这两个 handler 时，{@code /clinic/identify?patientId=0} 和
     * {@code /clinic/schedules?date=garbage} 都是 500 —— 和这个类自己批评的
     * 「认证失败不该是 500」是同一类问题：<b>预期内的客户端错误必须是 4xx</b>，
     * 500 会打堆栈刷盘、被压测统计成 error、还让前端误以为该重试。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badArgument(IllegalArgumentException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<Map<String, Object>> badDate(java.time.format.DateTimeParseException e) {
        return badRequest("日期格式不合法，应为 yyyy-MM-dd：" + e.getParsedString());
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "BAD_REQUEST");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
