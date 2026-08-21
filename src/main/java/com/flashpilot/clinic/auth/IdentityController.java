package com.flashpilot.clinic.auth;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 身份签发。<b>这是一个演示桩，不是真实的登录。</b>
 *
 * <p>真实系统这里必须校验密码或短信验证码。这个项目要证明的架构点是
 * 「<b>身份必须由服务端签发，客户端只能持有、不能声明</b>」——
 * 而不是重新实现一遍认证。
 *
 * <h2>为什么这个接口必须限流</h2>
 *
 * 它是整条链上唯一能挡住「批量换身份绕过风控」的地方。
 * 风控的频次判据按 patientId 计数，如果攻击者能无限量地为任意 patientId 换令牌，
 * 那么把 holderId 从 query 挪到令牌里<b>什么也没解决</b> ——
 * 他只是多调一次接口而已。
 *
 * <p>所以这里按<b>来源 IP</b> 限流，而不是按 patientId（按 patientId 限流毫无意义：
 * 攻击者换的就是 patientId）。这一点值得单独说，因为它是这类限流最常见的设计错误。
 */
@RestController
public class IdentityController {

    private static final Logger log = LoggerFactory.getLogger(IdentityController.class);

    /** 每个 IP 每窗口最多签发多少个令牌。 */
    private static final int MAX_PER_WINDOW = 20;

    /** 限流窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final PatientIdentity identity;

    /**
     * IP → 当前窗口内的签发次数。滑动窗口在这里是过度设计，固定窗口够用。
     *
     * <p><b>进程内计数，多实例下上限被放大 N 倍。</b>这道限流是挡「批量换身份绕过风控」
     * 的闸，要在多实例部署里认真依赖它就得把计数移到 Redis —— 演示环境先知道这回事
     * （README 已知缺陷里有这一条）。
     */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile long windowStartedAt = System.currentTimeMillis();

    public IdentityController(PatientIdentity identity) {
        this.identity = identity;
    }

    @PostMapping("/clinic/identify")
    public Map<String, Object> identify(@RequestParam long patientId, HttpServletRequest request) {
        if (patientId <= 0) {
            throw new IllegalArgumentException("patientId 必须为正");
        }
        rollWindowIfNeeded();

        String ip = clientIp(request);
        int used = counters.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
        if (used > MAX_PER_WINDOW) {
            log.warn("身份签发被限流：ip={} 本窗口已请求 {} 次（上限 {}）。"
                    + "这条日志值得关注 —— 正常用户不会在一分钟内换 {} 次身份，"
                    + "而批量换身份正是绕过风控频次判据的手法", ip, used, MAX_PER_WINDOW, MAX_PER_WINDOW);
            throw new TooManyIdentitiesException();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patientId);
        m.put("token", identity.issue(patientId));
        m.put("header", PatientIdentity.HEADER);
        m.put("note", "把这个令牌放在 " + PatientIdentity.HEADER
                + " 请求头里。**这是演示用的签发接口，真实系统此处必须校验密码或短信验证码。**");
        return m;
    }

    private void rollWindowIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - windowStartedAt >= WINDOW.toMillis()) {
            synchronized (this) {
                if (now - windowStartedAt >= WINDOW.toMillis()) {
                    counters.clear();
                    windowStartedAt = now;
                }
            }
        }
    }

    /**
     * 取来源 IP。
     *
     * <p><b>刻意不信任 X-Forwarded-For。</b>那个头是客户端可以随便写的，
     * 除非确知自己跑在一个会覆盖它的反向代理后面 —— 而在这里读它等于把限流的钥匙
     * 交给攻击者：他每次请求换一个假 XFF 就绕过了。
     * 真要支持代理，得配一个可信代理列表，那是部署时的决定，不该在代码里默认打开。
     */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public static class TooManyIdentitiesException extends RuntimeException {
        public TooManyIdentitiesException() {
            super("身份签发过于频繁，请稍后再试");
        }
    }
}
