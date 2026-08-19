package com.flashpilot.clinic.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashpilot.clinic.reconcile.ReconcileService.Outcome;

/**
 * 多池对账的汇总。
 *
 * <p>为什么值得单独测：对账原来只看一个号池（实验号池，默认 1001），
 * 真实排班的账目从来没被对过。改成遍历所有排班之后，多个池的结论必须汇总成一条给接口和看板，
 * <b>而汇总有一个很容易退化的判断：先报坏消息。</b>
 *
 * <p>按「多数」或「第一个」汇总，会把一个需要人介入的超卖告警埋在
 * 「另外 31 个池账目平衡」里 —— 那就等于没有告警。
 */
class ReconcileSummaryTest {

    private static Outcome ok(int vanished) {
        return new Outcome(false, vanished, 0, 0, "账目平衡");
    }

    private static Outcome refused(int vanished, String why) {
        return new Outcome(false, vanished, 0, 1, "拒绝自动处置：" + why);
    }

    private static Outcome acted(int vanished, int compensated) {
        return new Outcome(true, vanished, compensated, 3, "已补偿 " + compensated + " 个号源到活跃桶；复验通过，残差已归零");
    }

    @Test
    @DisplayName("全部平衡时说「全部账目平衡」，并报出扫了几个池")
    void allBalanced() {
        Outcome r = ReconcileService.summarize(32, List.of(ok(0), ok(0), ok(0)));
        assertThat(r.acted()).isFalse();
        assertThat(r.compensated()).isZero();
        assertThat(r.decision()).contains("扫了 32 个号池").contains("全部账目平衡");
    }

    @Test
    @DisplayName("一个池被拒绝，就必须出现在汇总里 —— 哪怕另外三十个都平衡")
    void oneRefusalSurfacesAmongManyBalanced() {
        List<Outcome> all = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            all.add(ok(0));
        }
        all.add(refused(-4365, "回收号源等于取消真实患者的预约，必须人工核查"));

        Outcome r = ReconcileService.summarize(32, all);
        assertThat(r.decision()).contains("拒绝自动处置").contains("人工核查");
        assertThat(r.vanished()).isEqualTo(-4365);
    }

    @Test
    @DisplayName("拒绝比「残差更大但已处理」优先 —— 严重性不看数字大小，看要不要人介入")
    void refusalOutranksLargerHandledResidual() {
        Outcome r = ReconcileService.summarize(3, List.of(
                acted(99, 99),                    // 残差更大，但已经自动处理掉了
                refused(-7, "潜在超卖"),           // 残差小得多，但需要人来看
                ok(0)));
        assertThat(r.decision()).contains("拒绝");
        assertThat(r.vanished()).isEqualTo(-7);
    }

    @Test
    @DisplayName("多个拒绝时挑残差绝对值最大的那个 —— 同样严重就看规模")
    void amongRefusalsPickLargest() {
        Outcome r = ReconcileService.summarize(3, List.of(
                refused(12, "小"), refused(-100000, "大"), refused(-30, "中")));
        assertThat(r.vanished()).isEqualTo(-100000);
    }

    @Test
    @DisplayName("补偿量是各池之和，动手池数进汇总文本")
    void compensationSums() {
        Outcome r = ReconcileService.summarize(4, List.of(
                acted(5, 5), acted(3, 3), ok(0), ok(0)));
        assertThat(r.acted()).isTrue();
        assertThat(r.compensated()).isEqualTo(8);
        assertThat(r.decision()).contains("2 个动了账目").contains("共补回 8 个号源");
    }

    @Test
    @DisplayName("空结果不抛异常 —— 所有池都查失败时也要给个能读的结论")
    void emptyList() {
        Outcome r = ReconcileService.summarize(5, List.of());
        assertThat(r.acted()).isFalse();
        assertThat(r.vanished()).isZero();
        assertThat(r.decision()).contains("扫了 5 个号池");
    }
}
