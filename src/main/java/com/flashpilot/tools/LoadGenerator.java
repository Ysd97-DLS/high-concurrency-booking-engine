package com.flashpilot.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内置压测器。零外部依赖，只用 JDK 自带的 HttpClient——
 * 因为 wrk 在 Windows 上装起来很麻烦，而「装不上工具」不该成为拿不到基线数据的理由。
 *
 * <p>它做的三件事恰好是 wrk 做不到或不好做的：
 * <ul>
 *   <li>按业务码分类统计（成功 / 售罄 / 限流 / 重复），而不是只看 HTTP 状态；</li>
 *   <li>客户端侧算 P50/P95/P99，和服务端的 Micrometer 数据可以互相印证；</li>
 *   <li>内置三种流量画像（恒定 / 脉冲 / 热点倾斜），对应实验方案里的 P1/P2/P3。</li>
 * </ul>
 *
 * <p>用法（先 {@code mvn compile}）：
 * <pre>
 *   java -cp target/classes com.flashpilot.tools.LoadGenerator
 *   java -cp target/classes com.flashpilot.tools.LoadGenerator --concurrency 400 --duration 30 --profile burst
 *   java -cp target/classes com.flashpilot.tools.LoadGenerator --profile skew --users 20000
 * </pre>
 *
 * <p>注意压测器和被压的服务跑在同一台机器上，会争抢 CPU。报告数字时必须写清这一点，
 * 关注的应该是同环境下不同方案的<b>差值</b>，而不是绝对值。
 */
public final class LoadGenerator {

    private LoadGenerator() {
    }

    private record Config(
            String baseUrl, long poolId, int concurrency, int durationSeconds,
            int users, String profile, int timeoutMs) {
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);
        System.out.printf("""
                ─────────────────────────────────────────────
                 FlashPilot 压测
                   目标      %s/seckill/%d
                   并发      %d
                   时长      %d 秒
                   用户空间  %d
                   画像      %s
                ─────────────────────────────────────────────
                %n""", cfg.baseUrl(), cfg.poolId(), cfg.concurrency(),
                cfg.durationSeconds(), cfg.users(), describeProfile(cfg.profile()));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        Map<String, AtomicLong> counters = new HashMap<>();
        for (String k : List.of("success", "sold_out", "rate_limited", "risk_dropped",
                                "duplicate", "error", "http_error")) {
            counters.put(k, new AtomicLong());
        }

        // 每个线程一个独立的延迟数组，避免共享结构成为瓶颈（压测器本身不能是瓶颈）
        List<long[]> perThread = new ArrayList<>();
        List<AtomicLong> perThreadCount = new ArrayList<>();
        int capacityPerThread = Math.max(1024, 400_000 / Math.max(1, cfg.concurrency()));

        ExecutorService pool = Executors.newFixedThreadPool(cfg.concurrency());
        CountDownLatch ready = new CountDownLatch(cfg.concurrency());
        CountDownLatch go = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.durationSeconds());

        for (int t = 0; t < cfg.concurrency(); t++) {
            long[] samples = new long[capacityPerThread];
            AtomicLong count = new AtomicLong();
            perThread.add(samples);
            perThreadCount.add(count);
            final int threadIndex = t;

            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    // 脉冲画像：所有线程在同一瞬间放开（burst 的本质就是零爬坡）
                    // 恒定画像：错开一点起始时间，避免整齐的锯齿
                    if ("constant".equals(cfg.profile())) {
                        Thread.sleep(ThreadLocalRandom.current().nextInt(200));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (System.nanoTime() < deadline) {
                    long holderId = nextUserId(cfg, threadIndex);
                    URI uri = URI.create(cfg.baseUrl() + "/seckill/" + cfg.poolId() + "?holderId=" + holderId);
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMillis(cfg.timeoutMs()))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    long start = System.nanoTime();
                    try {
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long elapsed = System.nanoTime() - start;
                        record(samples, count, elapsed);
                        if (response.statusCode() != 200) {
                            counters.get("http_error").incrementAndGet();
                        } else {
                            counters.get(classify(response.body())).incrementAndGet();
                        }
                    } catch (Exception e) {
                        record(samples, count, System.nanoTime() - start);
                        counters.get("error").incrementAndGet();
                    }
                }
            });
        }

        ready.await();
        long wallStart = System.nanoTime();
        go.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(cfg.durationSeconds() + 60L, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000.0;

        report(cfg, counters, perThread, perThreadCount, wallSeconds);
    }

    private static long nextUserId(Config cfg, int threadIndex) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if ("skew".equals(cfg.profile())) {
            // 热点倾斜：90% 的请求落到同一个桶上（holderId 全部同余），
            // 用来触发桶倾斜和借调。这正是 P3 画像要观察的东西。
            if (rnd.nextInt(100) < 90) {
                return (long) rnd.nextInt(cfg.users() / 8 + 1) * 32L;
            }
        }
        return rnd.nextInt(cfg.users()) + 1L;
    }

    private static void record(long[] samples, AtomicLong count, long nanos) {
        long idx = count.getAndIncrement();
        if (idx < samples.length) {
            samples[(int) idx] = nanos;
        }
    }

    /** 从响应体里抠业务码。够用且极快——压测器自己不能成为瓶颈。 */
    private static String classify(String body) {
        if (body == null) {
            return "error";
        }
        if (body.contains("\"code\":200")) {
            return "success";
        }
        if (body.contains("\"code\":4001")) {
            return "sold_out";
        }
        if (body.contains("\"code\":4002")) {
            return "duplicate";
        }
        if (body.contains("\"code\":4290")) {
            return "rate_limited";
        }
        // 4291 = 风控降权后慢车道也满了。**必须和 4290 分开统计。**
        // 原来后端把两者都返回 4290，于是 52 万次风控丢弃被报成「被限流拒绝」，
        // 整轮性能数字被污染而报告上完全看不出来 ——
        // 你以为在测引擎吞吐，实际测的是慢车道的 20/s。
        if (body.contains("\"code\":4291")) {
            return "risk_dropped";
        }
        return "error";
    }

    private static void report(Config cfg, Map<String, AtomicLong> counters,
                               List<long[]> perThread, List<AtomicLong> perThreadCount,
                               double wallSeconds) {
        long total = 0;
        for (AtomicLong c : counters.values()) {
            total += c.get();
        }

        int sampleTotal = 0;
        for (int i = 0; i < perThread.size(); i++) {
            sampleTotal += (int) Math.min(perThreadCount.get(i).get(), perThread.get(i).length);
        }
        long[] all = new long[sampleTotal];
        int pos = 0;
        for (int i = 0; i < perThread.size(); i++) {
            int n = (int) Math.min(perThreadCount.get(i).get(), perThread.get(i).length);
            System.arraycopy(perThread.get(i), 0, all, pos, n);
            pos += n;
        }
        Arrays.sort(all);

        long success = counters.get("success").get();

        System.out.printf("""
                ─────────────────────────────────────────────
                 结果
                ─────────────────────────────────────────────
                  实际耗时        %.2f s
                  总请求          %d
                  吞吐（总）      %.0f req/s
                  有效吞吐（成交） %.1f req/s

                  成功（成交）    %d
                  售罄            %d
                  重复购买        %d
                  被限流拒绝      %d   ← 主限流，控制面调的就是它
                  被风控丢弃      %d   ← 风控降权+慢车道，和限流无关
                  异常            %d
                  HTTP 非 200     %d%s

                  延迟（客户端观测，含排队）
                    P50           %.2f ms
                    P95           %.2f ms
                    P99           %.2f ms
                    最大          %.2f ms
                ─────────────────────────────────────────────
                 下一步：GET /verify/check 做一致性校验
                        GET /seckill/state/%d 看库存分布
                ─────────────────────────────────────────────
                %n""",
                wallSeconds, total, total / wallSeconds, success / wallSeconds,
                success,
                counters.get("sold_out").get(),
                counters.get("duplicate").get(),
                counters.get("rate_limited").get(),
                counters.get("risk_dropped").get(),
                counters.get("error").get(),
                counters.get("http_error").get(),
                riskWarning(counters.get("risk_dropped").get(), total),
                percentile(all, 0.50), percentile(all, 0.95), percentile(all, 0.99),
                all.length == 0 ? 0 : all[all.length - 1] / 1_000_000.0,
                cfg.poolId());
    }

    /**
     * 风控污染警告。
     *
     * <p><b>这段警告是这次踩坑最重要的产出。</b>原来风控丢弃和限流拒绝共用一个计数，
     * 一轮压测里 52 万个请求（95%）被风控丢进 20/s 的慢车道，
     * 而报告只显示"被限流拒绝 528224"——数字本身是对的，结论完全错了：
     * 你以为测出了引擎在限流下的表现，实际测的是慢车道的速率。
     *
     * <p>光把计数分开还不够，因为看报告的人未必知道 4291 意味着什么。
     * <b>让污染主动喊出来，而不是等人去发现。</b>
     * 一个平时不说话、出事时也不说话的指标，等于没有这个指标。
     */
    private static String riskWarning(long riskDropped, long total) {
        if (total <= 0 || riskDropped * 100 < total) {   // < 1% 视为正常
            return "";
        }
        double pct = riskDropped * 100.0 / total;
        return String.format("""

                  ⚠ 本轮 %.1f%% 的请求被风控丢弃，这一轮的性能数字<b>不代表引擎能力</b>。
                    压测流量的形态（大量患者各请求两三次）会命中患者频次判据。
                    要测引擎，先把 riskcontrol.threshold 调高：
                      POST /control/config?param=riskcontrol.threshold&value=500&reason=压测
                    要测风控本身，用 A/B 对照（正常患者组 vs 一机多号组），别用高压画像。""", pct);
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx] / 1_000_000.0;
    }

    private static String describeProfile(String profile) {
        return switch (profile) {
            case "burst" -> "burst（脉冲：所有并发在同一瞬间放开，对应 P2）";
            case "skew" -> "skew（热点倾斜：90% 流量压同一个桶，对应 P3）";
            default -> "constant（恒定高压，对应 P1）";
        };
    }

    private static Config parse(String[] args) {
        String baseUrl = "http://127.0.0.1:8090";
        long poolId = 1001L;
        int concurrency = 200;
        int duration = 20;
        int users = 200_000;
        String profile = "constant";
        int timeoutMs = 5000;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--url" -> baseUrl = args[++i];
                case "--item" -> poolId = Long.parseLong(args[++i]);
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--duration" -> duration = Integer.parseInt(args[++i]);
                case "--users" -> users = Integer.parseInt(args[++i]);
                case "--profile" -> profile = args[++i];
                case "--timeout" -> timeoutMs = Integer.parseInt(args[++i]);
                default -> {
                }
            }
        }
        return new Config(baseUrl, poolId, Math.max(1, concurrency), Math.max(1, duration),
                Math.max(1, users), profile, timeoutMs);
    }
}
