package com.flashpilot.dataplane.dedupe;

import org.springframework.stereotype.Component;

import com.flashpilot.config.FlashPilotProperties;
import com.flashpilot.dataplane.stock.LocalSegmentManager;
import com.flashpilot.dataplane.stock.StockRedisRepository;

/**
 * 一人一单的判重标记。
 *
 * <p><b>为什么值得单独一个组件。</b>原来这套「模式开关 + 打标 / 撤标」写在
 * {@code SeckillService} 的两个私有方法里。等到业务域也需要撤标时（退号 / 超时释放），
 * 唯一的出路是让 {@code clinic} 去依赖 {@code SeckillService} —— 而那是引擎的<b>编排入口</b>，
 * 业务域依赖它等于把整条热路径都拖进依赖图，方向也反了：
 * 业务域需要的只是「清掉这个人在这个池子里的标记」这一个能力。
 *
 * <p>抽出来之后 {@code SeckillService} 和 {@code AppointmentService} 都只依赖这个窄接口，
 * 而 LOCAL / REDIS 的模式判断也只剩一处（原来 mark 和 rollback 各写了一遍，
 * 改判重模式要记得改两个地方 —— 这种重复迟早会漏）。
 *
 * <h2>两种模式的取舍</h2>
 * <ul>
 *   <li><b>LOCAL</b>：进程内标记，零网络。<b>依赖网关的粘性路由</b>——同一个 holder 的请求
 *       必须落到同一个实例，否则同一个人在两个实例上各占一个名额（后一个会被
 *       MySQL 的 {@code active_key} 唯一索引挡掉，表现为白占一次库存名额再回滚）。</li>
 *   <li><b>REDIS</b>：跨实例强一致，代价是每请求一次 RTT ——
 *       而这条路径的全部意义就是避免 RTT（本地号段把 95% 的扣减挡在 Redis 之外），
 *       加回来就白省了。</li>
 * </ul>
 */
@Component
public class PurchaseDedupe {

    private final FlashPilotProperties props;
    private final LocalSegmentManager segments;
    private final StockRedisRepository stockRedis;

    public PurchaseDedupe(FlashPilotProperties props, LocalSegmentManager segments,
                          StockRedisRepository stockRedis) {
        this.props = props;
        this.segments = segments;
        this.stockRedis = stockRedis;
    }

    private boolean local() {
        return props.dedupe().mode() == FlashPilotProperties.Dedupe.Mode.LOCAL;
    }

    /** 打标。返回 false 表示这个 holder 在这个池子里已经占过号了。 */
    public boolean mark(long poolId, long holderId) {
        return local() ? segments.markBoughtLocal(poolId, holderId)
                : stockRedis.markBought(poolId, holderId);
    }

    /**
     * 撤标：这个 holder 在这个池子里<b>已经不占号了</b>。
     *
     * <p>三类调用方，理由完全相同：
     * <ul>
     *   <li>抢号中途失败（引擎内部）—— 号没拿到；</li>
     *   <li><b>患者退号</b> —— 号还回池子了；</li>
     *   <li><b>未支付超时释放</b> —— 号还回池子了。</li>
     * </ul>
     *
     * <p>后两类原来漏了，后果很重：<b>号回了池子，而患者被永久挡在门外。</b>
     * 超时未付款的患者尤其冤 —— 号已经放出去给别人抢了，他自己却再也约不了这个排班。
     * 而 schema 里那套 {@code active_key} 生成列（为此还专门绕过了 {@code ERROR 3109}）
     * 就是为「退号后能重约」做的，被热路径上更靠前的判重挡成了不可达。
     *
     * <p>这和 bug ⑨ 是同一个形状：<b>精心做好的能力，被流水线上更早的一道检查废掉了。</b>
     * 判据是：<b>凡是把号源还回池子的路径，都必须同时撤标</b>——
     * 两者是「这个人不再占号」的两半，少任何一半状态就不自洽。
     * 反过来 NO_SHOW 不还号源，也就<b>不该</b>撤标（患者正在被惩罚，不该能立刻重约）。
     */
    public void clear(long poolId, long holderId) {
        if (local()) {
            segments.unmarkBoughtLocal(poolId, holderId);
        } else {
            stockRedis.unmarkBought(poolId, holderId);
        }
    }
}
