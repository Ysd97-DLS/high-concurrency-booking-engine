package com.flashpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 注册表的过期判定。
 *
 * <p>这段逻辑决定「现在有几个实例在跑」，而那个数字决定 LOCAL 判重的告警要不要发。
 * 判错的代价不对称：<b>少数一个实例</b>会让告警不发，20% 的号源静默蒸发；
 * <b>多数一个</b>只是发一条多余的红字。所以下面的边界测试都偏向「宁可多数」。
 */
class InstanceRegistryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long STALE = 15_000L;

    private static Map<Object, Object> entries(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("新鲜度边界")
    class Freshness {

        @Test
        @DisplayName("刚心跳过的算活着")
        void fresh() {
            var split = InstanceRegistry.partition(entries("a", String.valueOf(NOW)), NOW, STALE);
            assertThat(split.alive()).containsExactly("a");
            assertThat(split.dead()).isEmpty();
        }

        @Test
        @DisplayName("正好等于阈值时仍算活着 —— 边界取闭区间，宁可多数一个")
        void exactlyAtThreshold() {
            var split = InstanceRegistry.partition(entries("a", String.valueOf(NOW - STALE)), NOW, STALE);
            assertThat(split.alive()).containsExactly("a");
            assertThat(split.dead()).isEmpty();
        }

        @Test
        @DisplayName("超过阈值 1 毫秒就判死")
        void oneMsPastThreshold() {
            var split = InstanceRegistry.partition(entries("a", String.valueOf(NOW - STALE - 1)), NOW, STALE);
            assertThat(split.alive()).isEmpty();
            assertThat(split.dead()).containsExactly("a");
        }

        @Test
        @DisplayName("时钟比本机快的实例算活着，不能判死")
        void futureTimestampCountsAsAlive() {
            // 判死会让两个时钟不同步的实例互相把对方从注册表删掉，
            // 然后各自以为自己是唯一实例 —— 正好绕过这里要防的告警。
            var split = InstanceRegistry.partition(entries("a", String.valueOf(NOW + 60_000)), NOW, STALE);
            assertThat(split.alive()).containsExactly("a");
            assertThat(split.dead()).isEmpty();
        }
    }

    @Nested
    @DisplayName("脏数据")
    class Malformed {

        @Test
        @DisplayName("值不是数字的清理掉，不影响其它实例的判定")
        void nonNumericIsCleaned() {
            var split = InstanceRegistry.partition(
                    entries("bad", "not-a-number", "good", String.valueOf(NOW)), NOW, STALE);
            assertThat(split.alive()).containsExactly("good");
            assertThat(split.dead()).containsExactly("bad");
        }

        @Test
        @DisplayName("时间戳带空白也能解析 —— 手动 HSET 调试时很容易带上")
        void trimsWhitespace() {
            var split = InstanceRegistry.partition(entries("a", "  " + NOW + "  "), NOW, STALE);
            assertThat(split.alive()).containsExactly("a");
        }

        @Test
        @DisplayName("空注册表返回两个空列表，不抛异常")
        void empty() {
            var split = InstanceRegistry.partition(entries(), NOW, STALE);
            assertThat(split.alive()).isEmpty();
            assertThat(split.dead()).isEmpty();
        }
    }

    @Nested
    @DisplayName("多实例场景")
    class MultiInstance {

        @Test
        @DisplayName("两个活实例都被认出来 —— 这是 LOCAL 判重该告警的场景")
        void twoAlive() {
            var split = InstanceRegistry.partition(
                    entries("inst-b", String.valueOf(NOW - 1000), "inst-a", String.valueOf(NOW)), NOW, STALE);
            assertThat(split.alive()).containsExactly("inst-a", "inst-b");   // 已排序
        }

        @Test
        @DisplayName("死实例不算进实例数 —— 否则重启过几次的部署会永远在告警")
        void deadNotCounted() {
            var split = InstanceRegistry.partition(entries(
                    "alive", String.valueOf(NOW),
                    "restarted-1", String.valueOf(NOW - 600_000),
                    "restarted-2", String.valueOf(NOW - 3_600_000)), NOW, STALE);
            assertThat(split.alive()).containsExactly("alive");
            assertThat(split.dead()).containsExactlyInAnyOrder("restarted-1", "restarted-2");
        }

        @Test
        @DisplayName("全部过期时 alive 为空，由调用方兜底成「至少有我自己」")
        void allStale() {
            var split = InstanceRegistry.partition(
                    entries("a", String.valueOf(NOW - 100_000), "b", String.valueOf(NOW - 200_000)), NOW, STALE);
            assertThat(split.alive()).isEmpty();
            assertThat(split.dead()).hasSize(2);
        }
    }
}
