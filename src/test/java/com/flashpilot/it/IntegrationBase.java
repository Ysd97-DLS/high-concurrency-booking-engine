package com.flashpilot.it;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import com.redis.testcontainers.RedisContainer;

/**
 * 集成测试的公共底座：真 Redis + 真 MySQL。
 *
 * <h2>为什么需要这一层</h2>
 *
 * README 里自认的最大测试缺口是「<b>测试只覆盖纯逻辑单元</b>」——
 * Lua 脚本、Stream 消费、护栏、放号链路都没有自动化测试，只能靠压测脚本端到端验证。
 * 那种验证有两个问题：<b>失败时定位不到层</b>（压测报「少卖 3 个」，是 Lua 错了、
 * 消费错了、还是放号错了？），以及<b>要人来跑</b>（起 Docker、灌数据、跑脚本、读报告，
 * 改一行代码没人愿意重来一遍）。
 *
 * <h2>两种容器来源，以及为什么默认不是 Testcontainers</h2>
 *
 * <ul>
 *   <li><b>默认：复用 {@code docker compose} 起好的 fp-redis / fp-mysql。</b>
 *       零启动开销，本机开发时容器本来就开着。</li>
 *   <li><b>{@code -Dit.containers=testcontainers}：由 Testcontainers 现起一套。</b>
 *       CI 里应该用这个 —— 它保证每次都是干净环境，不依赖本机开着什么。</li>
 * </ul>
 *
 * <p><b>默认值不是 Testcontainers，是因为这台开发机上它连不上 Docker。</b>
 * Docker Desktop 29.5.3 对 docker-java 的每个引擎命名管道
 * （{@code docker_engine} / {@code dockerDesktopLinuxEngine}）都返回一个字段全零的
 * <b>400 响应</b>，body 里只有 {@code com.docker.desktop.address=npipe://\\.\pipe\docker_cli}
 * 这一个标签；而 {@code docker} CLI 打同样的管道能正常拿到 info。
 * 也就是说不是守护进程没起，是 docker-java 走不通 Desktop 的这条协商路径。
 *
 * <p>解决办法（任选其一，都需要动本机环境，所以没写进默认值）：
 * <ol>
 *   <li>Docker Desktop 设置里勾上 <i>Expose daemon on tcp://localhost:2375 without TLS</i>，
 *       然后 {@code DOCKER_HOST=tcp://localhost:2375}；</li>
 *   <li>换用 CI 环境（Linux 上的 unix socket 没有这个问题）。</li>
 * </ol>
 *
 * <p>把测试写成两种来源都能跑，是为了<b>不让一个本机环境问题挡住测试本身的价值</b> ——
 * 断言内容一个字都不用改。
 */
@SpringBootTest
public abstract class IntegrationBase {

    /** {@code -Dit.containers=testcontainers} 时才由 Testcontainers 起容器。 */
    private static final boolean USE_TESTCONTAINERS =
            "testcontainers".equalsIgnoreCase(System.getProperty("it.containers", "compose"));

    /**
     * 建表脚本。<b>刻意不含 {@code 04-demo-data.sql}</b> ——
     * 测试要自己控制数据，依赖演示数据会让断言变得很脆。
     * MySQL 镜像按文件名字典序执行 initdb.d，所以这里的顺序就是执行顺序：
     * 03/05/06 是在 01/02 之上加列和索引的增量脚本。
     */
    private static final String[] SCHEMA = {
            "01-schema.sql", "02-clinic-schema.sql", "03-release-progress.sql",
            "05-reconcile-log.sql", "06-noshow-index.sql",
    };

    private static MySQLContainer<?> mysql;
    private static RedisContainer redis;

    static {
        if (USE_TESTCONTAINERS) {
            mysql = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("flashpilot")
                    .withUsername("root")
                    .withPassword("test")
                    .withReuse(true);
            for (String f : SCHEMA) {
                mysql.withCopyFileToContainer(
                        MountableFile.forHostPath("sql/" + f),
                        "/docker-entrypoint-initdb.d/" + f);
            }
            // noeviction 和生产一致 —— 库存 key 绝对不能被淘汰，
            // 被淘汰的表现是「号凭空消失」，而那正是这些测试要防的东西。
            redis = new RedisContainer(RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4-alpine"))
                    .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                    .withReuse(true);
            mysql.start();
            redis.start();
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        if (USE_TESTCONTAINERS) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }
        // 走 compose 时不覆盖任何连接参数，直接用 application.yml 里的 127.0.0.1。

        // 集成测试里把定时任务调到极长：它们会在断言中途改数据
        // （释放号源、对账补偿、失约扫描），让失败变得不可复现 ——
        // 而「不可复现的失败」比没有测试更消耗时间。
        registry.add("flashpilot.clinic.release-scan-ms", () -> "3600000");
        registry.add("flashpilot.clinic.no-show-scan-ms", () -> "3600000");
        registry.add("flashpilot.clinic.reconcile.interval-ms", () -> "3600000");
        registry.add("flashpilot.control.agent.enabled", () -> "false");
        // RocketMQ 整个自动配置排掉。
        //
        // 一开始是把 rocketmq.name-server 设成空串，结果启动直接失败：
        // 消费者容器（@RocketMQMessageListener 那个 BPP 建的）校验这个值非空。
        // 「设成空值让它自己不生效」这种做法只在组件明确支持时才成立，
        // 而更可靠的表达是**直接说不要这个自动配置**。
        //
        // 排掉之后 @RocketMQMessageListener 只是个没人处理的注解，
        // PayTimeoutConsumer 退化成普通 bean —— 正是这里想要的：
        // 这几个测试验的是号源账目的**正确性**，而 RocketMQ 管的是释放的**及时性**。
        registry.add("spring.autoconfigure.exclude",
                () -> "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration");
    }

    /**
     * 依赖不可用时跳过而不是失败。
     *
     * <p>这条判断的存在理由：集成测试红掉应该意味着<b>代码错了</b>，
     * 而不是「你忘了 docker compose up」。后者报 failure 会让人开始怀疑代码，
     * 而真正该做的只是起个容器 —— 用 Assumption 跳过并说清原因，
     * 比一个红叉加一屏连接超时堆栈有用得多。
     */
    protected static void requireInfrastructure(boolean reachable, String what) {
        Assumptions.assumeTrue(reachable,
                () -> what + " 不可用，跳过集成测试。本机跑：docker compose up -d；"
                        + "或加 -Dit.containers=testcontainers 让 Testcontainers 自己起"
                        + "（注意本机 Docker Desktop 有兼容问题，见类注释）");
    }
}
