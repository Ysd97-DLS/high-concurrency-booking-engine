package com.flashpilot.dataplane.stock;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis key 规划。
 *
 * <p><b>关于 MAX_BUCKETS 的设计决策</b>（这是个容易踩的坑，值得单独说明）：
 * 物理桶数<i>固定</i>为 32，控制面能调的只是「活跃桶数」——即请求哈希到前几个桶。
 *
 * <p>如果按直觉实现成「物理桶数 = 配置值」，那么把桶数从 8 调到 4 的瞬间，
 * 桶 4~7 里的库存就没有任何请求会碰到它们了，等于凭空少卖。
 * 而现在的实现里，借调循环始终扫描全部 32 个物理桶，
 * 所以高位桶里的存货一定会被捞出来，调小活跃桶数是<b>随时安全</b>的。
 */
public final class StockKeys {

    /** 物理桶数量，固定不变。活跃桶数（ConfigParam.ACTIVE_BUCKETS）只能 ≤ 这个值。 */
    public static final int MAX_BUCKETS = 32;

    private StockKeys() {
    }

    /** 桶库存：{@code sk:item:1001:b:0} */
    public static String bucket(long poolId, int index) {
        return "sk:item:" + poolId + ":b:" + index;
    }

    /** 租约表：{@code sk:item:1001:lease}，字段 h:<instance> 持有量、e:<instance> 到期时间 */
    public static String lease(long poolId) {
        return "sk:item:" + poolId + ":lease";
    }

    /** 一人一单的 Bitmap（REDIS 判重模式用）：{@code sk:item:1001:bought} */
    public static String bought(long poolId) {
        return "sk:item:" + poolId + ":bought";
    }

    /** 商品元信息：总库存等。 */
    public static String meta(long poolId) {
        return "sk:item:" + poolId + ":meta";
    }

    public static List<String> allBuckets(long poolId) {
        List<String> keys = new ArrayList<>(MAX_BUCKETS);
        for (int i = 0; i < MAX_BUCKETS; i++) {
            keys.add(bucket(poolId, i));
        }
        return keys;
    }

    /** 脚本约定：KEYS = 全部物理桶 + 租约表，所以租约表的下标是 MAX_BUCKETS+1。 */
    public static List<String> bucketsAndLease(long poolId) {
        List<String> keys = allBuckets(poolId);
        keys.add(lease(poolId));
        return keys;
    }

    /** 脚本约定：KEYS = 全部物理桶 + stream。 */
    public static List<String> bucketsAndStream(long poolId, String streamKey) {
        List<String> keys = allBuckets(poolId);
        keys.add(streamKey);
        return keys;
    }
}
