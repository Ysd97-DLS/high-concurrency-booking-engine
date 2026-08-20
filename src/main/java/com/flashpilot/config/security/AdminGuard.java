package com.flashpilot.config.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 运维接口的准入。挂在 {@code /admin/*}、{@code /verify/*}、{@code /control/*}、{@code /mcp*} 上。
 *
 * <h2>这些接口不加保护会发生什么（都是实测过的）</h2>
 *
 * <ul>
 *   <li>{@code POST /verify/preheat} 里面有 {@code DELETE FROM t_appointment} —— 一次请求清空全部预约；</li>
 *   <li>{@code POST /control/config?param=limit.qps&value=1} 把全局限流打到地板。
 *       实测护栏把它 clamp 到 100（原值 19825），也就是<b>护栏限制了幅度但没有阻止未授权修改</b>，
 *       吞吐直接掉到 1/198 —— 这是一次完整的拒绝服务；</li>
 *   <li>{@code POST /admin/patients/{id}/unblock} 把因失约被禁约的号贩子放出来 ——
 *       风控体系里唯一「真正拒绝」的手段被一个未授权请求撤销；</li>
 *   <li>{@code POST /admin/reconcile/run?dryRun=false} 直接改号源账目；</li>
 *   <li>{@code POST /control/agent/tick} 每次调用都会真实请求一次大模型 —— <b>按次计费</b>，
 *       所以它同时是一个经济型 DoS。</li>
 * </ul>
 *
 * <h2>准入规则：{@code 放行 = 来自本机 || 令牌正确}</h2>
 *
 * <p><b>为什么本机直接放行</b>：能在本机发 HTTP 请求的人，同样能读到配置文件里的令牌 ——
 * 对这种攻击者做 HTTP 认证是自欺欺人。而它换来的是本地开发和压测脚本零摩擦，
 * 这一点很重要：<b>一个让本地开发变麻烦的安全措施，最后会被人用「临时关掉」的方式绕过。</b>
 *
 * <p><b>为什么没配令牌时不是「全放行 + 告警」</b>：那等于默认不设防，
 * 而告警会在几万行日志里被淹没。默认拒绝、需要时显式打开，是唯一不会静默失效的方向。
 *
 * <h2>两个必须知道的陷阱 —— 「什么算本机」比看起来复杂</h2>
 *
 * <b>① 反向代理。</b>如果本机跑着 Nginx 之类的反向代理，
 * <b>所有转发过来的请求 remoteAddr 都是回环地址</b> ——
 * 于是「本机直接放行」把整个互联网都放进来了。这时必须设
 * {@code flashpilot.admin.trust-loopback=false}，改用纯令牌校验。
 * 这里刻意<b>不去读 X-Forwarded-For 来补救</b>：那个头客户端可以随便写，
 * 拿它做判据等于把钥匙交给攻击者。
 *
 * <p><b>② Docker Desktop（Windows / macOS）。</b>容器访问宿主机时流量经过 NAT，
 * <b>到达应用时源地址是 127.0.0.1</b>。实测：
 * <pre>
 * docker run --rm curlimages/curl -X POST http://host.docker.internal:8090/admin/...
 *   → 日志里 AdminGuard 记录的源地址是 127.0.0.1，不是容器的 172.17.x.x
 * </pre>
 * 也就是说默认配置下<b>本机上任何一个容器都能调运维接口</b>。
 * 这台机器上跑着 Redis、MySQL、Prometheus、Grafana 四个容器，
 * 其中 Grafana 是允许匿名访问的 —— 攻击链是存在的。
 *
 * <p>顺带一个方法论上的教训：验证这个过滤器时，「从容器里打过来被放行了」
 * 一度让我以为过滤器没生效。实际是<b>测试方法本身不成立</b> ——
 * 那个请求并不是外部请求。换成从本机的 WLAN 地址（192.168.1.3）打才是真的外部，
 * 结果是 403。<b>安全测试里「攻击成功」和「攻击路径没走通」长得一模一样，
 * 必须先证明测试用例真的构造出了目标条件。</b>
 */
public class AdminGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    /** 运维令牌请求头。 */
    public static final String HEADER = "X-Admin-Token";

    private final String token;
    private final boolean trustLoopback;

    public AdminGuard(String token, boolean trustLoopback) {
        this.token = token == null ? "" : token.trim();
        this.trustLoopback = trustLoopback;
        describeOnStartup();
    }

    private void describeOnStartup() {
        boolean hasToken = !token.isEmpty();
        if (hasToken && trustLoopback) {
            log.info("运维接口准入：本机放行 + 令牌校验（{}）", HEADER);
        } else if (hasToken) {
            log.info("运维接口准入：仅令牌校验（{}），本机不特殊对待 —— 适合有反向代理的部署", HEADER);
        } else if (trustLoopback) {
            log.warn("""
                    ════════ 运维接口只对本机开放 ════════
                    /admin/*、/verify/*、/control/*、/mcp 现在只接受来自本机的请求，
                    外部请求一律 403。本地开发与压测不受影响。
                    要从别的机器访问，设 ADMIN_TOKEN=<随机串> 并在请求里带 {} 头。
                    ⚠ 如果本机跑着反向代理（Nginx 等），转发来的请求看起来都像本机 ——
                      那种部署必须设 flashpilot.admin.trust-loopback=false。
                    ════════════════════════════════════""", HEADER);
        } else {
            log.error("""
                    ════════ 运维接口已全部关闭 ════════
                    既没有配置 ADMIN_TOKEN，又设了 trust-loopback=false，
                    于是 /admin/*、/verify/*、/control/* 对任何来源都返回 403（包括本机）。
                    这大概不是你想要的 —— 配一个 ADMIN_TOKEN，或者把 trust-loopback 打开。
                    ════════════════════════════════════""");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (allowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("拒绝未授权的运维请求：{} {} 来自 {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        // 说清怎么合法访问。这里不泄露任何东西 —— 提示的是「要带令牌」，
        // 而不是令牌是什么；攻击者从 403 本身就已经知道接口存在了。
        response.getWriter().write("""
                {"ok":false,"error":"FORBIDDEN",\
                "message":"运维接口不对外开放。本机访问，或在 %s 请求头里带上运维令牌。"}"""
                .formatted(HEADER));
    }

    private boolean allowed(HttpServletRequest request) {
        if (trustLoopback && isLoopback(request.getRemoteAddr())) {
            return true;
        }
        if (token.isEmpty()) {
            return false;
        }
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断是不是回环地址。
     *
     * <p>用 {@link InetAddress#isLoopbackAddress()} 而不是字符串比 {@code "127.0.0.1"}：
     * 回环地址有一整个 {@code 127.0.0.0/8} 段，IPv6 还有 {@code ::1}，
     * 而 Tomcat 给出的可能是 {@code 0:0:0:0:0:0:0:1} 这种写法。
     * 逐个字符串枚举一定会漏，漏的方向是<b>误拒本机</b> —— 那会让人以为服务坏了。
     */
    static boolean isLoopback(String addr) {
        if (addr == null || addr.isBlank()) {
            return false;
        }
        // 先挡掉非 IP 字面量，再交给 InetAddress。
        //
        // 原因是 getByName 只在参数是 IP 字面量时才不查 DNS ——
        // 传进去一个主机名它会**真的发起解析并阻塞**。实测：单元测试里那几个畸形输入
        // （"not-an-address" 之类）让这个类的测试从毫秒级涨到 2.7 秒。
        //
        // 眼下不会发生，因为 Tomcat 的 getRemoteAddr() 返回的必然是 socket 对端 IP。
        // 但这里离「有人改成读 X-Forwarded-For」只有一行之遥，
        // 那一刻每个请求都会带一次攻击者指定的 DNS 查询 —— 一个现成的放大型 DoS。
        // 所以这道检查不是为当下写的，是为那次改动写的。
        if (!looksLikeIpLiteral(addr)) {
            return false;
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 只含 IP 字面量允许的字符（十六进制数字、点、冒号、IPv6 区域符 %）。 */
    private static boolean looksLikeIpLiteral(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
