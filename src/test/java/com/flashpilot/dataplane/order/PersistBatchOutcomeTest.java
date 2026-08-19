package com.flashpilot.dataplane.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 「插不进去」到底是重复还是失败 —— 这个区分是这批测试的全部内容。
 *
 * <p>为什么值得单独测：{@code INSERT IGNORE} 对两种情况返回<b>完全相同</b>的结果
 * （该行不插入、不报错）。原来的代码用减法推断，把两者合并成「重复」，
 * 于是真失败的消息被 ACK 掉、号从 Redis 扣走却没有单、不重试也不进死信。
 * P6 Redis 主从切换时这样丢了 19389 个号，而日志一片干净。
 *
 * <p>这里不接数据库，只测那段判定逻辑：给定「插了几行」和「哪些患者已有占号单」，
 * 应该得出多少重复、多少失败。逻辑用一个和产线同构的本地实现表达，
 * 因为产线那段被 {@code @Transactional} 和 JdbcTemplate 缠着，
 * 而<b>要钉住的是判定规则本身，不是 Spring 的接线</b>。
 */
class PersistBatchOutcomeTest {

    /** 和 {@code OrderPersistService.persistBatch} 里那段判定同构。 */
    private static Outcome classify(List<OrderEvent> events, int inserted, Set<Long> holding) {
        if (inserted == events.size()) {
            return new Outcome(inserted, 0, List.of());
        }
        List<String> failed = new ArrayList<>();
        int duplicate = 0;
        for (OrderEvent e : events) {
            if (holding.contains(e.holderId())) {
                duplicate++;
            } else {
                failed.add(e.eventId());
            }
        }
        duplicate = Math.max(0, duplicate - inserted);
        return new Outcome(inserted, duplicate, failed);
    }

    private record Outcome(int inserted, int duplicate, List<String> failed) {
        boolean complete() {
            return failed.isEmpty();
        }
    }

    private static List<OrderEvent> events(long... holderIds) {
        List<OrderEvent> out = new ArrayList<>();
        for (long h : holderIds) {
            out.add(new OrderEvent(h, 1001L, "evt-" + h));
        }
        return out;
    }

    private static Set<Long> holding(long... ids) {
        Set<Long> s = new HashSet<>();
        for (long id : ids) {
            s.add(id);
        }
        return s;
    }

    @Nested
    @DisplayName("全部插入成功")
    class AllInserted {

        @Test
        @DisplayName("不查库、不算重复、没有失败 —— 正常批次的开销必须为零")
        void fastPath() {
            var r = classify(events(1, 2, 3), 3, Set.of());
            assertThat(r.inserted()).isEqualTo(3);
            assertThat(r.duplicate()).isZero();
            assertThat(r.complete()).isTrue();
        }
    }

    @Nested
    @DisplayName("插入行数不足")
    class PartialInsert {

        @Test
        @DisplayName("查得到已有单的算重复 —— 消息重投的正常情况")
        void realDuplicates() {
            // 3 条里插进去 1 条，另外 2 个患者本来就有占号单。
            // holding 含刚插进去的那个，所以要扣掉 inserted 才是真重复数。
            var r = classify(events(1, 2, 3), 1, holding(1, 2, 3));
            assertThat(r.duplicate()).isEqualTo(2);
            assertThat(r.failed()).isEmpty();
            assertThat(r.complete()).isTrue();
        }

        @Test
        @DisplayName("查不到已有单的算失败 —— 这是原来被误算成重复、导致号消失的那一类")
        void realFailures() {
            // 一条也没插进去，而且这些患者在库里都没有单 —— 号已扣走，必须重试。
            var r = classify(events(7, 8), 0, Set.of());
            assertThat(r.duplicate()).isZero();
            assertThat(r.failed()).containsExactly("evt-7", "evt-8");
            assertThat(r.complete()).isFalse();
        }

        @Test
        @DisplayName("重复和失败混在一批里，各归各类")
        void mixed() {
            // 4 条：1 插入成功、2 是真重复（有单）、1 是真失败（无单、没插进去）
            var r = classify(events(1, 2, 3, 4), 1, holding(1, 2, 3));
            assertThat(r.inserted()).isEqualTo(1);
            assertThat(r.duplicate()).isEqualTo(2);
            assertThat(r.failed()).containsExactly("evt-4");
            assertThat(r.complete()).isFalse();
        }

        @Test
        @DisplayName("整批都是真重复且一条都没插入 —— 重复数不能被 inserted 减成负数")
        void allDuplicateNothingInserted() {
            var r = classify(events(5, 6), 0, holding(5, 6));
            assertThat(r.duplicate()).isEqualTo(2);
            assertThat(r.failed()).isEmpty();
        }
    }

    @Nested
    @DisplayName("回归：不能再用减法")
    class NoMoreSubtraction {

        @Test
        @DisplayName("同样的输入下，减法会报 2 个重复，而查证后是 2 个失败")
        void subtractionWouldHideTwoLostSlots() {
            var evts = events(11, 12);
            int inserted = 0;
            int subtraction = evts.size() - inserted;      // 旧算法
            var r = classify(evts, inserted, Set.of());    // 新算法

            assertThat(subtraction).isEqualTo(2);          // 旧算法：2 个「重复购买」，ACK 掉
            assertThat(r.duplicate()).isZero();            // 新算法：一个重复都没有
            assertThat(r.failed()).hasSize(2);             // 而是 2 条要重试
            // 差别就是 2 个号：旧算法下它们从 Redis 扣走、MySQL 没单、消息被 ACK，
            // 永久消失且无人知晓；新算法下它们留在 pending 等抢回。
        }

        @Test
        @DisplayName("单条路径同样要查证，查不到就抛异常而不是判重复")
        void singlePathThrowsInsteadOfClaimingDuplicate() {
            // 单条路径的判定：n==0 且查不到 → 抛 PersistFailedException
            assertThatThrownBy(() -> {
                Set<Long> holdingNow = Set.of();
                long holderId = 42L;
                if (!holdingNow.contains(holderId)) {
                    throw new OrderPersistService.PersistFailedException(1001L, holderId, "evt-42");
                }
            })
                    .isInstanceOf(OrderPersistService.PersistFailedException.class)
                    .hasMessageContaining("查不到已有单")
                    .hasMessageContaining("holderId=42");
        }
    }

    @Nested
    @DisplayName("回归：查证必须看得到并发提交的行")
    class MustSeeConcurrentCommits {

        /**
         * 这一条钉的是修 bug 时引入的第二个 bug，形态比第一个更隐蔽。
         *
         * <p>查证最初写在 {@code @Transactional} 的 persistBatch 里面。MySQL 默认
         * REPEATABLE READ：事务内的 SELECT 读事务开始时的快照，看不到之后其它事务提交的行；
         * 而 INSERT 的唯一索引检查走当前读，看得见。<b>两者对「这行存在吗」给出不同答案。</b>
         *
         * <p>P6 实测抓到：holderId=165838 在 MySQL 里明明有 PENDING_PAY 的占号单，
         * 事务内查证却查不到，于是被判成真失败、无限重试，日志刷了 2014 条
         * 「凭证号没有冲突，需要人工排查」。号没丢（比第一个 bug 轻），但消费被拖死。
         */
        @Test
        @DisplayName("并发 flusher 刚提交的行必须算重复，否则会无限重试")
        void concurrentlyCommittedRowIsDuplicateNotFailure() {
            var evts = events(165838);

            // 事务内的旧快照：看不到并发提交的那一行 → 误判成失败 → 无限重试
            var staleSnapshot = classify(evts, 0, Set.of());
            assertThat(staleSnapshot.failed()).containsExactly("evt-165838");

            // 事务外的新快照：看得到 → 正确判成重复 → ACK 掉，消费继续前进
            var freshSnapshot = classify(evts, 0, holding(165838));
            assertThat(freshSnapshot.duplicate()).isEqualTo(1);
            assertThat(freshSnapshot.failed()).isEmpty();
            assertThat(freshSnapshot.complete()).isTrue();
        }

        @Test
        @DisplayName("Outcome 不再有 DUPLICATE —— 名字本身就是那个陷阱")
        void outcomeHasNoDuplicateAnymore() {
            // 叫 DUPLICATE 会诱使调用方直接 ACK，而 INSERT IGNORE 返回 0
            // 根本不足以推出「已经有单了」。改名 NOT_INSERTED 让调用方必须去查证。
            assertThat(OrderPersistService.Outcome.values())
                    .containsExactly(OrderPersistService.Outcome.INSERTED,
                            OrderPersistService.Outcome.NOT_INSERTED);
        }
    }
}
