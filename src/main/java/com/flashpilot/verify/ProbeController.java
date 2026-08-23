package com.flashpilot.verify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 给外部压测编排工具用的探针。
 *
 * <h2>它和 {@code /verify/check} 有什么不同</h2>
 *
 * {@code /verify/check} 返回的是<b>结论</b>：五条等式各自过没过、有没有超卖少卖。
 * 那套等式写死在 {@link ConsistencyChecker} 里，改一条要改这个仓库的代码、重新部署。
 *
 * <p>这里返回的是<b>原始的量</b>，不含任何判断：初始号数、桶剩余、实例持有、
 * 占号预约数、已消费、重复、死信……等式由外部工具在它自己的场景文件里声明并求值。
 *
 * <p>这个划分是刻意的，理由有两条：
 * <ol>
 *   <li><b>加一条等式不该需要改被测系统。</b>同一个系统在不同实验里可能被不同的约束检查，
 *       把等式钉死在系统里，等于每设计一个新实验就要发一次版。</li>
 *   <li><b>校验器和被测对象是同一份代码时，它证明不了什么。</b>
 *       等式③ 的算法本身就修过一次（原来把退号还回桶的号重复计数了）——
 *       一个由外部独立表达的等式，至少不会和被测系统一起犯同一个错。</li>
 * </ol>
 *
 * <h2>为什么不直接返回 {@code ConsistencyChecker.Report}</h2>
 *
 * 那个记录里混着原始量（{@code bucketSum}）和派生结论（{@code oversold}、{@code passed}）。
 * 把结论一起递出去，外部工具会忍不住直接用 {@code passed == 1} 当判据，
 * 于是绕了一圈又回到「系统自己判自己」。这里只给原料。
 */
@RestController
@RequestMapping("/probe")
public class ProbeController {

    private final ConsistencyChecker checker;
    private final VerifyController verify;

    public ProbeController(ConsistencyChecker checker, VerifyController verify) {
        this.checker = checker;
        this.verify = verify;
    }

    /**
     * 一次调用返回全部的量，由本方法保证这份快照<b>内部一致</b>。
     *
     * <p>外部工具需要的是「现在，立刻，把这几个数一起给我」——
     * 从 Prometheus 拉是不行的：那边各指标按各自的抓取周期落点，几个量之间天然差几秒，
     * 而对一个每秒成交几千笔的系统，几秒的错位会造出比真实缺陷大得多的残差。
     *
     * <p>{@code probe()} 而不是 {@code check()}：后者会落库并打日志，
     * 而收敛轮询每半秒调一次，一轮实验能写出几百行噪声。
     */
    @GetMapping("/invariants")
    public Map<String, Object> invariants() {
        ConsistencyChecker.Report r = checker.probe();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolId", r.poolId());
        // ── 号源在谁手里 ────────────────────────────────────────────
        m.put("initialStock", r.initialStock());
        m.put("bucketSum", r.bucketSum());
        m.put("leaseHeld", r.leaseHeld());
        m.put("orderCount", r.orderCount());
        m.put("soldStock", r.soldStock());
        // ── 消息链路 ────────────────────────────────────────────────
        m.put("streamLength", r.streamLength());
        m.put("consumed", r.consumed());
        m.put("duplicate", r.duplicate());
        m.put("deadLetter", r.deadLetter());
        m.put("oversoldBlocked", r.oversoldBlocked());
        m.put("unprocessed", r.unprocessed());
        // ── 采样本身可不可信 ────────────────────────────────────────
        // 采样期间若有号源正在归还，号源守恒等式必然差几个 —— 那是非原子采样的产物，
        // 不是缺陷。外部工具可以把它写进收敛条件（stableSample == 1）而不是等式里。
        m.put("stableSample", r.stableSample() ? 1 : 0);
        return m;
    }

    /**
     * 恢复到干净起点。
     *
     * <p>参数默认值与 {@code /verify/preheat} 一致，因为它就是同一件事 ——
     * 这里只是给外部工具一个不需要知道 {@code /verify} 存在的入口。
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "1001") long poolId,
                                     @RequestParam(defaultValue = "1000") int totalStock,
                                     @RequestParam(defaultValue = "8") int buckets) {
        return verify.preheat(poolId, totalStock, buckets);
    }
}
