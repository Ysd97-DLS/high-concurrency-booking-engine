package com.flashpilot.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 回环地址判定。
 *
 * <p>这个判据两个方向的错法后果不一样，所以两边都要测：
 * <ul>
 *   <li><b>误判为本机</b>（把外部地址当本机）= 安全漏洞，运维接口对外开放；</li>
 *   <li><b>误判为外部</b>（把本机当外部）= 本地开发和压测脚本全部 403，
 *       表现为「服务好像坏了」，会有人直接把这个过滤器关掉。</li>
 * </ul>
 * 第二种更可能真实发生 —— 因为回环地址不只有 {@code 127.0.0.1} 一个写法。
 */
@DisplayName("运维准入：回环地址判定")
class AdminGuardTest {

    @ParameterizedTest
    @DisplayName("这些都是本机 —— 漏掉任何一个都会误拒本地请求")
    @ValueSource(strings = {
            "127.0.0.1",
            // 整个 127.0.0.0/8 都是回环。字符串比较 "127.0.0.1" 会漏掉这些，
            // 而 Docker 的端口转发、某些代理确实会出现 127.0.0.x。
            "127.0.0.2",
            "127.1.2.3",
            "127.255.255.254",
            // IPv6 回环。Tomcat 在双栈机器上给出的往往是这个形态，
            // 而不是人们下意识去比的 "127.0.0.1" —— 这是最容易踩的一个。
            "::1",
            "0:0:0:0:0:0:0:1",
    })
    void loopback(String addr) {
        assertThat(AdminGuard.isLoopback(addr)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("这些都不是本机 —— 尤其内网地址不能算本机")
    @ValueSource(strings = {
            "192.168.1.5",     // 同一个局域网里的另一台机器，最现实的攻击来源
            "10.0.0.7",
            "172.17.0.2",      // Docker 默认网段：容器互访不该被当成本机
            "8.8.8.8",
            "0.0.0.0",         // 通配地址，不是回环
            "2001:db8::1",
            "::",
    })
    void notLoopback(String addr) {
        assertThat(AdminGuard.isLoopback(addr)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("拿不到来源地址时判为「不是本机」—— 默认拒绝")
    @ValueSource(strings = { "", " ", "not-an-address", "999.999.999.999" })
    void unparseableIsRejected(String addr) {
        // 方向很重要：解析不出来就拒绝，而不是放行。
        // 反过来写的话，一个畸形的 remoteAddr 就能拿到运维权限。
        assertThat(AdminGuard.isLoopback(addr)).isFalse();
    }

    @Test
    @DisplayName("null 判为「不是本机」，不抛异常")
    void nullIsRejected() {
        assertThat(AdminGuard.isLoopback(null)).isFalse();
    }

    @Test
    @DisplayName("受保护的路径清单里必须包含四类危险接口")
    void guardedPathsCoverTheDangerousSurfaces() {
        // 这条断言是防回归用的：以后有人加了新的运维接口前缀却忘了挂过滤器，
        // 单看代码是发现不了的 —— 它会安静地对外开放。
        assertThat(SecurityConfig.GUARDED)
                .contains("/admin/*")     // 解封号贩子、改号源账目
                .contains("/verify/*")    // preheat 里有 DELETE FROM t_appointment
                .contains("/control/*")   // 改限流参数 = 拒绝服务；agent/tick 按次计费
                .contains("/mcp");        // 模型控制面入口
    }
}
