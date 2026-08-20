package com.flashpilot.clinic.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 患者令牌的验签行为。
 *
 * <p>这组测试守的是一件事：<b>令牌的 patientId 部分不能被改动而仍然通过验签。</b>
 * 如果能，那整套所有权校验和风控频次判据都会退回到「客户端自己报身份」的状态 ——
 * 也就是修复之前的样子。
 */
@DisplayName("患者令牌")
class PatientTokenTest {

    private static final byte[] SECRET = "test-secret-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "another-secret-abcdefg".getBytes(StandardCharsets.UTF_8);

    @Nested
    @DisplayName("正常签发与验证")
    class HappyPath {

        @Test
        @DisplayName("签发的令牌能验回同一个 patientId")
        void roundTrip() {
            String token = PatientToken.issue(5501L, SECRET);
            assertThat(PatientToken.verify(token, SECRET)).hasValue(5501L);
        }

        @Test
        @DisplayName("同一个患者反复签发得到相同令牌 —— 无状态，不需要存储")
        void deterministic() {
            assertThat(PatientToken.issue(42L, SECRET))
                    .isEqualTo(PatientToken.issue(42L, SECRET));
        }

        @Test
        @DisplayName("不同患者的令牌不同")
        void distinctPerPatient() {
            assertThat(PatientToken.issue(1L, SECRET))
                    .isNotEqualTo(PatientToken.issue(2L, SECRET));
        }

        @Test
        @DisplayName("Long.MAX_VALUE 也能正常往返 —— 边界不该有特殊分支")
        void maxValue() {
            String token = PatientToken.issue(Long.MAX_VALUE, SECRET);
            assertThat(PatientToken.verify(token, SECRET)).hasValue(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("伪造与篡改（这一组是整个修复的核心）")
    class Tampering {

        @Test
        @DisplayName("改掉 patientId 但保留原签名 —— 必须拒绝")
        void swappedPatientId() {
            String legit = PatientToken.issue(5501L, SECRET);
            String mac = legit.substring(legit.indexOf('.') + 1);
            // 这就是攻击者最直接的尝试：拿自己的令牌，把 ID 换成受害者的。
            String forged = "8801." + mac;
            assertThat(PatientToken.verify(forged, SECRET)).isEmpty();
        }

        @Test
        @DisplayName("两个合法令牌交换签名部分 —— 必须都拒绝")
        void crossedSignatures() {
            String a = PatientToken.issue(111L, SECRET);
            String b = PatientToken.issue(222L, SECRET);
            String macA = a.substring(a.indexOf('.') + 1);
            String macB = b.substring(b.indexOf('.') + 1);

            assertThat(PatientToken.verify("111." + macB, SECRET)).isEmpty();
            assertThat(PatientToken.verify("222." + macA, SECRET)).isEmpty();
        }

        @Test
        @DisplayName("签名改一个字符 —— 必须拒绝")
        void mutatedSignature() {
            String token = PatientToken.issue(5501L, SECRET);
            char last = token.charAt(token.length() - 1);
            String mutated = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
            assertThat(PatientToken.verify(mutated, SECRET)).isEmpty();
        }

        @Test
        @DisplayName("用另一个密钥签的令牌 —— 必须拒绝")
        void wrongSecret() {
            String token = PatientToken.issue(5501L, OTHER);
            assertThat(PatientToken.verify(token, SECRET)).isEmpty();
        }

        @Test
        @DisplayName("裸的 patientId（没有签名）—— 必须拒绝")
        void bareIdIsNotAToken() {
            // 这一条守的是「退回到旧行为」这个具体的回归：
            // 旧接口收的就是裸的 holderId，万一验签逻辑写成了「有就信」，
            // 攻击者只要少发一半内容就绕过了。
            assertThat(PatientToken.verify("5501", SECRET)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("畸形输入一律拒绝，不抛异常")
        @ValueSource(strings = {
                "", " ", ".", "..", ".abc", "abc.", "5501.", "notanumber.abc",
                "5501.abc.def", "-1.abc", "0.abc", "  .  ", "5501 . abc",
        })
        void malformed(String token) {
            assertThat(PatientToken.verify(token, SECRET)).isEmpty();
        }

        @Test
        @DisplayName("null 令牌返回 empty，不是 NPE")
        void nullToken() {
            // 直接对应「请求没带那个头」的情况 —— 最常见的输入，不能炸。
            assertThat(PatientToken.verify(null, SECRET)).isEqualTo(OptionalLong.empty());
        }

        @Test
        @DisplayName("patientId 为 0 或负数一律拒绝")
        void nonPositiveId() {
            // 就算签名对得上也拒。0 和负数在业务上不是合法患者，
            // 而它们最可能来自「未初始化的变量被签进了令牌」这类 bug ——
            // 那种令牌一旦流出去，就是一个所有人共用的身份。
            assertThat(PatientToken.verify(PatientToken.issue(0L, SECRET), SECRET)).isEmpty();
            assertThat(PatientToken.verify(PatientToken.issue(-5L, SECRET), SECRET)).isEmpty();
        }
    }

    @Nested
    @DisplayName("令牌格式")
    class Format {

        @Test
        @DisplayName("是 URL 安全的，可以直接放进请求头或查询串")
        void urlSafe() {
            String token = PatientToken.issue(5501L, SECRET);
            // base64url 用 - 和 _ 代替 + 和 /，并且去掉了 = 填充。
            // 带 + 或 / 的话放进 query string 会被转义、放进头也容易出问题。
            assertThat(token).matches("^[0-9]+\\.[A-Za-z0-9_-]+$");
        }

        @Test
        @DisplayName("不含密钥的任何片段")
        void doesNotLeakSecret() {
            String token = PatientToken.issue(5501L, SECRET);
            assertThat(token).doesNotContain(new String(SECRET, StandardCharsets.UTF_8));
        }
    }
}
