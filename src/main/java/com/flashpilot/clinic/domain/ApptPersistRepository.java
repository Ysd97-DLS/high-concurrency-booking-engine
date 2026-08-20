package com.flashpilot.clinic.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.flashpilot.clinic.domain.mapper.ApptPersistMapper;
import com.flashpilot.dataplane.order.OrderEvent;

/**
 * 成交事件 → 预约单 的持久化适配器。取代原来写 {@code t_order} 的 OrderRepository。
 *
 * <p>它是引擎和业务域的接缝：引擎只知道"某个 holder 从某个 pool 拿到了一个单位"，
 * 由这里把它翻译成一张有状态机、有就诊时间、有支付时限的预约单。
 *
 * <p><b>就诊序号（seqNo）是这一层新增的职责。</b>引擎的事件里没有序号——它只管计数，
 * 不知道"第几个"。而挂号必须有序号才能推算就诊时间（8:00 那批 / 8:15 那批…）。
 * 用 Redis 计数器按批预留一段连续区间：一批一次 INCRBY，本地分配偏移量，
 * 既避免了多个 flusher 线程抢同一个号，也只多一次 Redis 往返。
 */
@Repository
public class ApptPersistRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApptPersistRepository.class);

    /** 支付时限。真实系统常见 15~30 分钟，这里取 10 分钟便于观察超时释放。 */


    private final ApptPersistMapper mapper;
    private final StringRedisTemplate redis;
    private final ScheduleRepository schedules;
    private final AppointmentRepository appts;
    private final com.flashpilot.config.FlashPilotProperties props;

    /** 号池静态信息缓存。排班建好后不会变，没必要每批查库。 */
    private final java.util.Map<Long, ScheduleRepository.PoolInfo> poolCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ApptPersistRepository(ApptPersistMapper mapper, StringRedisTemplate redis,
                                ScheduleRepository schedules, AppointmentRepository appts,
                                com.flashpilot.config.FlashPilotProperties props) {
        this.mapper = mapper;
        this.redis = redis;
        this.schedules = schedules;
        this.appts = appts;
        this.props = props;
    }

    private ScheduleRepository.PoolInfo pool(long poolId) {
        return poolCache.computeIfAbsent(poolId, schedules::find);
    }

    private static String seqKey(long poolId) {
        return "clinic:seq:" + poolId;
    }

    /**
     * 把一批成交事件落成待支付预约单。
     *
     * @return 实际插入行数。被 uk_active（一人一号）或消息重投挡掉的不计入。
     */
    public int insertBatch(long poolId, List<OrderEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        ScheduleRepository.PoolInfo info = pool(poolId);
        if (info == null) {
            throw new IllegalStateException("排班不存在，poolId=" + poolId + "；先建排班再放号");
        }

        // 一次 INCRBY 预留连续序号区间，本地再分配。多 flusher 并发也不会撞号。
        Long end = redis.opsForValue().increment(seqKey(poolId), events.size());
        long startSeq = (end == null ? events.size() : end) - events.size() + 1;

        LocalDateTime deadline = LocalDateTime.now().plusMinutes(props.clinic().payMinutes());
        List<AppointmentRepository.PendingAppt> batch = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            OrderEvent e = events.get(i);
            int seq = (int) (startSeq + i);
            LocalTime visitTime = info.visitTimeOf(seq);
            batch.add(new AppointmentRepository.PendingAppt(
                    "A" + poolId + "-" + seq,
                    poolId,
                    e.holderId(),
                    info.doctorId(),
                    info.visitDate(),
                    seq,
                    visitTime,
                    info.feeCents(),
                    deadline,
                    e.eventId()));
        }
        int n = appts.insertPendingBatch(batch);
        if (n == 0 && !batch.isEmpty()) {
            diagnoseAndRealign(poolId, batch);
        }
        return n;
    }

    /**
     * 整批一条都没插进去时，查清到底为什么，并在确认是序号回退时校准。
     *
     * <p>触发场景实测于 P6（Redis 哨兵主从切换）：反复出现
     * 「批大小=256 插入=0 重复=0」—— 一条也没插入，而这批患者在库里都没有单。
     * {@code INSERT IGNORE} 对此不报错、不区分原因，只返回 0。
     *
     * <p>唯一能解释的是 {@code uk_appt_no} 冲突：appt_no 里的序号来自 Redis 的
     * {@code INCRBY}，而 <b>Redis 主从是异步复制，从库提主时未同步的自增会丢</b> ——
     * 序号回退到已经用过的区间，生成的凭证号撞上已落库的行。
     *
     * <p>校准的方向只能是<b>往前推</b>，权威取 MySQL 的 {@code MAX(seq_no)}：
     * Redis 是缓存，MySQL 才是账本，这条原则对号源成立，对序号同样成立。
     * 用 Lua 做「只在更大时才写」保证多 flusher 并发校准不会互相踩，
     * 也保证重复校准是幂等的。
     *
     * <p>不校准的话，重试会一直落在同一段冲突区间里 —— 实测消费 5 分钟都追不平，
     * 每一轮都是「插入 0」。校准之后重试就能拿到干净的序号。
     */
    private void diagnoseAndRealign(long poolId, List<AppointmentRepository.PendingAppt> batch) {
        List<String> nos = new ArrayList<>(batch.size());
        for (AppointmentRepository.PendingAppt p : batch) {
            nos.add(p.apptNo());
        }
        int collided;
        try {
            collided = appts.countExistingApptNos(nos);
        } catch (Exception e) {
            log.warn("整批未插入，但凭证号冲突检查失败 poolId={}：{}", poolId, e.toString());
            return;
        }
        if (collided == 0) {
            log.error("整批 {} 条未插入，且凭证号没有冲突 —— 原因不是序号回退，需要人工排查 poolId={}",
                    batch.size(), poolId);
            return;
        }
        int mysqlMax = appts.maxSeqNo(poolId);
        long target = (long) mysqlMax + 1;
        boolean moved = realignSeq(poolId, target);
        log.error("""
                整批 {} 条未插入，其中 {} 个凭证号已被占用 —— Redis 序号回退了（主从切换的典型后果）。
                已按 MySQL 的 MAX(seq_no)={} 把 clinic:seq:{} 校准到 {}（实际写入={}）。
                这批消息保留在 pending，重试时会拿到干净的序号。""",
                batch.size(), collided, mysqlMax, poolId, target, moved);
    }

    /**
     * 只在目标值更大时才写序号。用 Lua 而不是 GET+SET：
     * 多个 flusher 会同时发现回退并同时校准，读改写非原子会让其中一个把序号写小回去。
     */
    private boolean realignSeq(long poolId, long target) {
        String lua = """
                local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
                local want = tonumber(ARGV[1])
                if want > cur then
                  redis.call('SET', KEYS[1], want)
                  return 1
                end
                return 0
                """;
        try {
            Long r = redis.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                    List.of(seqKey(poolId)), String.valueOf(target));
            return r != null && r == 1L;
        } catch (Exception e) {
            log.warn("序号校准失败 poolId={} target={}：{}", poolId, target, e.toString());
            return false;
        }
    }

    /**
     * 这批患者里哪些已经有占号单。用于在插入行数不足时区分
     * 「真重复」和「真失败」—— 见 {@link AppointmentRepository#findHoldingPatients}。
     */
    public java.util.Set<Long> findHoldingPatients(long poolId, java.util.List<Long> patientIds) {
        return appts.findHoldingPatients(poolId, patientIds);
    }

    /** 批量累加已生效号数。一批一次 UPDATE，避免十万行抢同一把行锁。 */
    public boolean incrementBookedBy(long poolId, int n) {
        if (n <= 0) {
            return true;
        }
        return mapper.incrementBookedBy(poolId, n) == 1;
    }

    /**
     * 号源归还时把排班上的已占号数减回去。
     *
     * <p><b>漏掉这一步会让 {@code booked_slots} 永久漂移。</b>它在建单时 +1，
     * 如果归还路径不 -1，那么每发生一次退号或超时释放，这个字段就比真实占号数多 1，
     * 而它正是患者端"剩余 X 号"的数据源 —— 漂移到最后会显示"已满"而其实还有号。
     * 实测退一个号之后等式⑤ 立刻报 {@code 占号预约 3 == booked_slots 4} 不一致。
     *
     * <p>下限保护到 0：即使上游重复调用也不会变成负数。
     */
    public void decrementBookedBy(long poolId, int n) {
        if (n <= 0) {
            return;
        }
        mapper.decrementBookedBy(poolId, n);
    }

    /** 已落库的预约总数（含所有状态）。用于和消费入库数这个累计量对齐。 */
    public int countAllAppts(long poolId) {
        return orZero(mapper.countAllAppts(poolId));
    }

    /**
     * <b>全部</b>号池的预约行数，不限 poolId。
     *
     * <p>等式⑤ 的累计量组需要它。「消费入库数」是<b>全局</b>计数器
     * （Stream 键 {@code fp:stream:order} 只有一条，所有号池共用），
     * 拿它去比某一个号池的行数，只在"全场只有这一个号池有流量"时才成立。
     *
     * <p>这个前提在压测时成立、在真实使用时立刻不成立：实测过一次——
     * 压测完在演示排班上挂了<b>一个</b>号，等式⑤ 立刻报
     * 「落库预约 116 == 消费入库 117 不一致」。数据完全正确，
     * 纯粹是拿「一个池的行数」比「所有池的事件数」。
     *
     * <p>正确的比法是两边都取全局，再减掉 preheat 时的基线：
     * preheat 之后新增的每一条预约，无论落在哪个号池，都恰好对应一个消费入库事件。
     */
    public int countAllApptsGlobal() {
        return orZero(mapper.countAllApptsGlobal());
    }

    /** 回滚单张（抢号后落库前失败的补偿路径）。 */
    public void deleteAppt(long holderId, long poolId) {
        mapper.deleteAppt(holderId, poolId);
    }

    // ---------- 一致性校验用的读接口 ----------

    /** 号池总号数，等式③ 的初始值。 */
    public int totalSlots(long poolId) {
        return orZero(mapper.totalSlots(poolId));
    }

    /** 排班上记录的已生效号数。 */
    public int bookedSlots(long poolId) {
        return orZero(mapper.bookedSlots(poolId));
    }

    /**
     * 占着号源的预约数。
     *
     * <p>这个数取代了原来的"订单数"。<b>差别很关键</b>：订单只有"存在/不存在"两种状态，
     * 而预约单有六种状态，其中只有四种占着号源。EXPIRED 和 REFUNDED 的号已经还回桶了，
     * 会被"Σ桶剩余"计入，如果这里再算一遍，等式③ 会凭空多出一截。
     */
    public int countHoldingSlots(long poolId) {
        return appts.countHoldingSlots(poolId);
    }

    /** 待支付预占数。单列出来是为了让等式③ 能看出号卡在哪一环。 */
    public int countPendingPay(long poolId) {
        return appts.countPendingPay(poolId);
    }

    public void resetForExperiment(long poolId, int totalSlots) {
        appts.resetForExperiment(poolId, totalSlots);
        // 序号计数器也要清，否则 apptNo 会从上一轮的尾号继续涨（不影响正确性但难对照）
        redis.delete(seqKey(poolId));
        poolCache.remove(poolId);
    }

    /** COUNT/字段查询在「行不存在」时给 null，统一折成 0。 */
    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
