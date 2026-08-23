package com.flashpilot.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 把 {@link AdminGuard} 挂到需要保护的路径上。
 *
 * <p><b>为什么是白名单式的「列出要保护的路径」而不是「保护全部、排除公开的」</b>：
 * 这个项目的公开面（患者端 + 前端静态资源 + Prometheus 抓取端点）比运维面大得多，
 * 反过来写会更容易漏。而漏的方向不同 —— 漏了要保护的接口是安全漏洞，
 * 漏了要公开的接口只是功能坏掉，会立刻被发现。
 *
 * <p>这里刻意<b>没有引入 Spring Security</b>：它会带来一整套过滤器链、
 * 默认的 CSRF 与登录页行为，而这个项目要证明的是并发正确性与控制面自治。
 * 一个 60 行的过滤器解决了同样的问题，且没有任何隐式行为需要读文档才能理解。
 */
@Configuration
public class SecurityConfig {

    /** 需要运维准入的路径。改这里的时候记得同步 README 的安全说明。 */
    static final String[] GUARDED = {
            "/admin/*",
            "/verify/*",
            // 探针返回的是内部状态的原始量（号源在谁手里、消息链路各段的计数），
            // 和 /verify 同级别，同样只对运维面开放。
            "/probe/*",
            "/control/*",
            "/mcp",
            "/mcp/*",
            // 库存分布诊断。它不返回任何患者数据，但会暴露实例 ID、号段命中率、
            // 借调次数、异常计数、桶倾斜度 —— 这些是运维视角的内部状态，
            // 能帮攻击者判断「现在还剩多少号、哪个实例是热点、什么时候借调」。
            //
            // 收进来不会弄坏任何东西：前端声明了 poolState 却**从来没有页面调用它**，
            // 而压测脚本都跑在本机，走的是回环放行那条路。
            "/seckill/state/*",
    };

    @Bean
    public FilterRegistrationBean<AdminGuard> adminGuard(
            @Value("${flashpilot.admin.token:}") String token,
            @Value("${flashpilot.admin.trust-loopback:true}") boolean trustLoopback) {

        FilterRegistrationBean<AdminGuard> reg =
                new FilterRegistrationBean<>(new AdminGuard(token, trustLoopback));
        // Servlet 的 "/admin/*" 只匹配一层以下的全部路径，够用；
        // 注意它不匹配 "/admin" 本身，而项目里没有这个裸路径。
        reg.addUrlPatterns(GUARDED);
        // 排在最前面：任何业务逻辑、任何日志、任何指标统计都不该在准入之前发生。
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
