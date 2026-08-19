package com.flashpilot.clinic.risk;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Count-Min Sketch：用<b>固定内存</b>估计海量 key 的出现频次。
 *
 * <p><b>为什么风控这里必须用它而不是 HashMap 或 Redis。</b>
 * 频次判据要回答「这个患者/设备/IP 最近请求了多少次」，key 空间是几十万患者 × 设备 × IP。
 * <ul>
 *   <li>用 {@code HashMap<String, Counter>}：每个 key 一个对象，几十万 key 就是几十万次分配，
 *       而且放号高峰时 key 空间会突然膨胀，GC 压力直接打到热路径上；</li>
 *   <li>用 Redis 计数：每请求一次 RTT。这条路径的全部意义就是<i>避免</i>网络往返
 *       （本地号段把 95% 的扣减请求挡在 Redis 之外），风控再加回来就白干了；</li>
 *   <li>用 CMS：内存固定（这里 4 × 8192 个 long ≈ 256KB），O(1) 读写，零分配。</li>
 * </ul>
 *
 * <p><b>代价是它会高估，永不低估。</b>哈希冲突会把别人的计数算到你头上，
 * 所以判据只能是「计数超过阈值就<i>怀疑</i>」，不能是「计数就是真值」。
 * 这恰好符合风控的需求：宁可多怀疑几个再用更贵的 L2/L3 去核实，
 * 也不要漏掉真黄牛。<b>但它绝对不能直接用来拉黑</b>——高估意味着会冤枉真实患者。
 *
 * <p>滑动窗口用「双缓冲 + 定时轮转」实现：写当前桶，读当前桶 + 上一个桶。
 * 比精确的时间轮便宜得多，代价是窗口边界不精确（最多多算一个窗口的量），
 * 对频次判据来说完全够用。
 *
 * <h2>噪声底：这个类最重要的一条量化约束</h2>
 *
 * <p>高估不是"一点点"，它有确定的量级：<b>窗口内事件总数 ÷ width</b>。
 * 每一行的 width 个计数器要分摊窗口内的所有事件，所以任意 key 的估计值
 * 天然带着这么多别人的计数。<b>阈值必须远大于噪声底，否则判据恒真。</b>
 *
 * <p>这个坑我踩得很实：width=8192、窗口 10 秒（估计跨 2 个窗口 = 20 秒），
 * 压测 27,666 req/s 打进来 → 窗口内约 55 万个事件 → 噪声底 ≈ 55万/8192 ≈ <b>67</b>。
 * 而阈值是 3。于是 55 万个请求<b>每一个</b>都被判定为高频，全部降权进 20/s 的慢车道，
 * 丢掉 52 万个。而表面现象只是"成交数偏低、误拒率高"，
 * 看不出风控参与了——<b>风控在最该工作的高峰期变成了全局熔断器。</b>
 *
 * <p>两处修复：
 * <ol>
 *   <li>width 加大到 65536（8 倍），噪声底同比降到 1/8；</li>
 *   <li><b>估计值里把噪声底减掉</b>（{@code corrected = max(0, min − total/width)}）。
 *       这是标准的 count-mean-min 修正。代价是不再保证"永不低估"，
 *       但当前的行为是 100% 误判，比偶尔漏一个黄牛坏得多——
 *       而且这个取舍和风控既定原则一致：<b>误判急需就诊的患者，代价远高于放过黄牛。</b></li>
 * </ol>
 *
 * <p>更根本的一点值得记住：<b>CMS 适合找"重头"（占总量一定比例的 key），
 * 不适合判定绝对小数。</b>阈值 3 相对 55 万总量是 0.0005%，
 * 任何亚线性草图都分辨不出这个量级。真要卡绝对小数，只能靠缩短窗口把总量降下来。
 */
public final class CountMinSketch {

    private final int depth;
    private final int width;
    private final int[] seeds;

    /** 双缓冲：一个在写，一个是上一窗口的历史。轮转时清空旧的那个。 */
    private volatile AtomicLongArray current;
    private volatile AtomicLongArray previous;

    /**
     * 窗口内的事件总数，用来算噪声底。
     *
     * <p>和 current/previous 同步轮转。多一个 AtomicLong 的争用换来判据在高负载下依然可用，
     * 非常值得——没有它就无法知道"估计值里有多少是别人的"。
     */
    private volatile AtomicLongArray currentTotal = new AtomicLongArray(1);
    private volatile AtomicLongArray previousTotal = new AtomicLongArray(1);

    public CountMinSketch(int depth, int width) {
        if (Integer.bitCount(width) != 1) {
            throw new IllegalArgumentException("width 必须是 2 的幂，位运算取模才够快");
        }
        this.depth = depth;
        this.width = width;
        this.seeds = new int[depth];
        for (int i = 0; i < depth; i++) {
            // 固定种子：同一个 key 在任何实例上都散列到同一批槽位，便于排查
            this.seeds[i] = 0x9E3779B9 * (i + 1);
        }
        this.current = new AtomicLongArray(depth * width);
        this.previous = new AtomicLongArray(depth * width);
    }

    /** 计一次，并返回计数后的估计值（当前窗口 + 上一窗口，已扣除噪声底）。 */
    public long incrementAndEstimate(String key) {
        int h = key.hashCode();
        long min = Long.MAX_VALUE;
        AtomicLongArray cur = current;
        AtomicLongArray prev = previous;
        currentTotal.incrementAndGet(0);
        for (int i = 0; i < depth; i++) {
            int idx = slot(h, i);
            long c = cur.incrementAndGet(idx) + prev.get(idx);
            if (c < min) {
                min = c;
            }
        }
        // 取各行最小值：这是 CMS 的核心 —— 每一行都可能因冲突被高估，
        // 但真实计数一定 ≤ 所有行的值，所以最小值是最接近真值的上界。
        return correct(min);
    }

    /** 只估计不计数。 */
    public long estimate(String key) {
        int h = key.hashCode();
        long min = Long.MAX_VALUE;
        AtomicLongArray cur = current;
        AtomicLongArray prev = previous;
        for (int i = 0; i < depth; i++) {
            int idx = slot(h, i);
            long c = cur.get(idx) + prev.get(idx);
            if (c < min) {
                min = c;
            }
        }
        return correct(min);
    }

    /**
     * 扣掉噪声底：{@code max(0, min − 窗口事件总数 / width)}。
     *
     * <p>为什么必须扣：每一行的 width 个计数器分摊窗口内所有事件，
     * 所以任意 key 的估计值天然含有约 {@code total/width} 个别人的计数。
     * 不扣的话，高负载下这个量会盖过阈值本身，判据恒真。
     * 实测：55 万事件 / width 8192 ≈ 67，而阈值是 3。
     *
     * <p>这是 count-mean-min 修正的思路。代价是不再保证"永不低估"，
     * 取舍见类注释。
     */
    private long correct(long rawMin) {
        long total = currentTotal.get(0) + previousTotal.get(0);
        long noiseFloor = total / width;
        return Math.max(0, rawMin - noiseFloor);
    }

    /** 当前的噪声底，运维可见。看板上这个数接近或超过阈值就说明判据已经失效。 */
    public long noiseFloor() {
        return (currentTotal.get(0) + previousTotal.get(0)) / width;
    }

    private int slot(int hash, int row) {
        int mixed = hash ^ seeds[row];
        // 再混一次，避免 String.hashCode 低位分布不均导致同一行内聚集
        mixed ^= (mixed >>> 16);
        return row * width + (mixed & (width - 1));
    }

    /**
     * 窗口轮转：当前桶变成历史，历史桶清零后接着写。
     *
     * <p>用「换引用」而不是「逐槽清零」：清 32768 个槽位在轮转瞬间会拖慢所有并发请求，
     * 而换引用是一次 volatile 写。被换下去的数组交给 GC，反正只有两个。
     */
    public void rotate() {
        previous = current;
        // 复用被换下来的数组会省一次分配，但要先清零 —— 清零的成本又回来了。
        // 直接新建让 GC 处理更简单，而轮转频率是秒级，这点分配无所谓。
        current = new AtomicLongArray(depth * width);
        // 事件总数必须和计数桶<b>同步</b>轮转，否则噪声底和实际计数对不上：
        // 总数留着旧窗口的量而桶已清零 → 噪声底虚高 → 判据永远不触发（漏掉真黄牛）。
        previousTotal = currentTotal;
        currentTotal = new AtomicLongArray(1);
    }

    public int memoryBytes() {
        return depth * width * Long.BYTES * 2;
    }
}
