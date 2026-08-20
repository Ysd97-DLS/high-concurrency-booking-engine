package com.flashpilot.clinic.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求里取出可信的患者身份。
 *
 * <p>密钥来源与它的取舍：配置了 {@code flashpilot.clinic.token-secret} 就用它，
 * 没配就<b>启动时随机生成一个并打红字</b>。随机生成的后果是重启后旧令牌全部失效
 * （用户要重新「登录」），对演示环境完全可以接受；
 * 而反过来 —— 给一个硬编码的默认密钥 —— 会让所有部署共享同一个签名密钥，
 * 那时任何人都能替任何人签发身份，比不做认证更糟，因为它看起来是安全的。
 */
@Component
public class PatientIdentity {

    private static final Logger log = LoggerFactory.getLogger(PatientIdentity.class);

    /** 请求头名。用自定义头而不是 Cookie：这样天然不受 CSRF 影响（浏览器不会自动带上它）。 */
    public static final String HEADER = "X-Patient-Token";

    private final byte[] secret;

    public PatientIdentity(@Value("${flashpilot.clinic.token-secret:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
            log.info("患者令牌密钥来自配置（长度 {} 字节）", this.secret.length);
        } else {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("""
                    ════════ 未配置患者令牌密钥，已随机生成 ════════
                    flashpilot.clinic.token-secret 为空，本次启动随机生成了一个密钥。
                    后果：重启后所有已签发的令牌失效，前端需要重新「登录」。演示环境无所谓。
                    **刻意不给硬编码默认值** —— 那会让所有部署共享同一个签名密钥，
                    任何人都能替任何人签发身份，比不做认证更糟（因为它看起来是安全的）。
                    要固定就设环境变量：PATIENT_TOKEN_SECRET=<足够长的随机串>
                    ════════════════════════════════════════""");
        }
    }

    /** 签发令牌。调用方负责验证凭据 —— 这个类不管「你是不是本人」，只管签名。 */
    public String issue(long patientId) {
        return PatientToken.issue(patientId, secret);
    }

    /** 从请求头取患者身份。没有或验签失败返回 empty。 */
    public OptionalLong of(HttpServletRequest request) {
        return PatientToken.verify(request.getHeader(HEADER), secret);
    }

    /**
     * 取患者身份，取不到就抛 401。
     *
     * <p>给热路径用的形态：调用方不必写 if-else，而 {@link UnauthenticatedException}
     * 由统一的异常处理转成 401，不会漏成 500。
     */
    public long require(HttpServletRequest request) {
        return of(request).orElseThrow(UnauthenticatedException::new);
    }

    /** 缺少或无效的患者令牌。 */
    public static class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException() {
            super("缺少或无效的患者令牌，请先调用 POST /clinic/identify 获取");
        }
    }

    /** 密钥指纹，只用于启动日志与自检 —— 绝不暴露密钥本身。 */
    public String secretFingerprint() {
        byte[] head = new byte[Math.min(4, secret.length)];
        System.arraycopy(secret, 0, head, 0, head.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(head) + "…";
    }
}
