package com.flashpilot.clinic.api;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashpilot.clinic.AppointmentService;
import com.flashpilot.clinic.domain.Appointment;
import com.flashpilot.clinic.domain.AppointmentRepository;
import com.flashpilot.clinic.domain.ScheduleRepository;

/**
 * 患者端接口。
 *
 * <p>业务旅程：选科室 → 看排班 → 抢号（走 /seckill 引擎接口）→ 支付 → 我的预约 → 退号。
 * 抢号本身刻意不放在这里 —— 它是引擎的热路径，走 {@code POST /seckill/{poolId}}，
 * 这个 Controller 只管旅程的其余部分。这条边界就是"引擎"和"业务"的分界线。
 */
@RestController
@RequestMapping("/clinic")
public class ClinicController {

    private final ScheduleRepository schedules;
    private final AppointmentRepository appts;
    private final AppointmentService service;

    public ClinicController(ScheduleRepository schedules, AppointmentRepository appts,
                            AppointmentService service) {
        this.schedules = schedules;
        this.appts = appts;
        this.service = service;
    }

    /**
     * 服务端当前时间。前端用它校准本地时钟偏移，再去算支付倒计时。
     *
     * <p><b>为什么必须有这个接口</b>：支付倒计时不能用浏览器的 {@code Date.now()} 直接算。
     * 用户的系统时间可能偏几分钟甚至几小时（虚拟机、手动改过时区的机器很常见）。
     * 偏快会让还能支付的单显示「已超时」，用户直接放弃；
     * 偏慢会让早就该释放的单一直倒计时，用户点了支付才发现失败。
     *
     * <p>前端只需要在启动时校准一次：记录请求前后的本地时间，减掉半个 RTT，
     * 算出偏移量，之后所有倒计时都用「本地时间 + 偏移」。
     */
    @GetMapping("/server-time")
    public Map<String, Object> serverTime() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epochMs", System.currentTimeMillis());
        m.put("iso", java.time.LocalDateTime.now().toString());
        return m;
    }

    @GetMapping("/departments")
    public List<Map<String, Object>> departments() {
        return schedules.listDepartments();
    }

    /** 某科室某天的可约排班。前端第一屏。 */
    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules(
            @RequestParam long departmentId,
            @RequestParam(required = false) String date) {
        LocalDate d = date == null ? LocalDate.now().plusDays(1) : LocalDate.parse(date);
        return schedules.listOpen(departmentId, d);
    }

    @GetMapping("/appointments")
    public List<Map<String, Object>> myAppointments(@RequestParam long patientId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        List<Appointment> list = appts.findByPatient(patientId, limit);
        // 一次把所有涉及的排班信息取回来，避免 N+1。见 ScheduleRepository#viewByIds
        Map<Long, Map<String, Object>> extra = schedules.viewByIds(
                list.stream().map(Appointment::scheduleId).collect(Collectors.toSet()));
        return list.stream().map(a -> toView(a, extra.get(a.scheduleId()))).toList();
    }

    @GetMapping("/appointments/{apptNo}")
    public Map<String, Object> detail(@PathVariable String apptNo) {
        Appointment a = appts.findByApptNo(apptNo);
        if (a == null) {
            return Map.of("found", false);
        }
        return toView(a, schedules.viewByIds(Set.of(a.scheduleId())).get(a.scheduleId()));
    }

    /**
     * 模拟支付。
     *
     * <p>刻意不接真实支付网关：这个项目要验证的是<b>超时释放</b>，
     * 而不是支付集成。真实网关的异步回调、对账、幂等是另一个题目。
     */
    @PostMapping("/appointments/{apptNo}/pay")
    public Map<String, Object> pay(@PathVariable String apptNo) {
        return reply(service.pay(apptNo), apptNo);
    }

    @PostMapping("/appointments/{apptNo}/refund")
    public Map<String, Object> refund(@PathVariable String apptNo) {
        return reply(service.refund(apptNo), apptNo);
    }

    @PostMapping("/appointments/{apptNo}/complete")
    public Map<String, Object> complete(@PathVariable String apptNo) {
        return reply(service.complete(apptNo), apptNo);
    }

    @PostMapping("/appointments/{apptNo}/no-show")
    public Map<String, Object> noShow(@PathVariable String apptNo) {
        return reply(service.noShow(apptNo), apptNo);
    }

    private Map<String, Object> reply(AppointmentService.Result r, String apptNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r == AppointmentService.Result.OK);
        m.put("result", r.name());
        m.put("apptNo", apptNo);
        m.put("message", switch (r) {
            case OK -> "操作成功";
            case NOT_FOUND -> "预约单不存在";
            case WRONG_STATE -> "当前状态不允许该操作（可能已被超时释放或已处理）";
            case NOT_REFUNDABLE -> "距就诊不足 2 小时，不可退号";
        });
        Appointment a = appts.findByApptNo(apptNo);
        if (a != null) {
            m.put("status", a.status().name());
            m.put("statusLabel", a.status().label());
        }
        return m;
    }

    /**
     * 预约单的患者视图。
     *
     * @param extra 该排班的医生 / 科室 / 时段信息，可以为 null（排班被删了的历史单）。
     *              <b>必须容忍 null</b>：预约单是历史记录、排班是当前配置，
     *              两者生命周期不同，缺了补充信息也要能把预约本身显示出来，
     *              不能让「我的预约」整页因为一条脏数据打不开。
     */
    private static Map<String, Object> toView(Appointment a, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apptNo", a.apptNo());
        m.put("scheduleId", a.scheduleId());
        m.put("patientId", a.patientId());
        m.put("doctorId", a.doctorId());
        m.put("visitDate", a.visitDate() == null ? null : a.visitDate().toString());
        m.put("visitTime", a.visitTime() == null ? null : a.visitTime().toString());
        m.put("seqNo", a.seqNo());
        m.put("status", a.status().name());
        m.put("statusLabel", a.status().label());
        m.put("feeYuan", a.feeCents() / 100.0);
        // 两个字段刻意都给：字符串给人看，epoch 毫秒给代码算。
        //
        // <b>只给字符串是个真 bug。</b>`LocalDateTime.toString()` 产出
        // "2026-08-18T20:30:30" —— <b>不带时区</b>。前端 `new Date(那个字符串)`
        // 按 ECMAScript 规范会当成<b>客户端本地时间</b>解析，而它其实是<b>服务端</b>
        // 本地时间。两者时区不同时倒计时就彻底错了，实测（服务端 CST、真实剩余 10 分）：
        //   客户端 Asia/Shanghai → 10:00 ✓
        //   客户端 UTC           → 490:00
        //   客户端 New_York      → 730:00
        //
        // 而这<b>恰好废掉了前端坑② 的全部意义</b>：那套 server-time 校准修的是
        // 时钟<i>偏移</i>，修不了时区<i>误读</i> —— 患者以为还有几小时，号早被释放了。
        //
        // 教训和前端那个 toISOString 差一天是同一个：
        // <b>时间戳跨进程传递时必须自带时区，或者干脆传 epoch。</b>epoch 没有歧义。
        m.put("payDeadline", a.payDeadline() == null ? null : a.payDeadline().toString());
        m.put("payDeadlineMs", a.payDeadline() == null ? null
                : a.payDeadline().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        // 医生和科室是患者最需要确认的信息，缺了这一页就没法用
        m.put("doctorName", extra == null ? null : extra.get("doctorName"));
        m.put("doctorTitle", extra == null ? null : extra.get("title"));
        m.put("departmentName", extra == null ? null : extra.get("departmentName"));
        m.put("period", extra == null ? null : extra.get("period"));
        m.put("slotType", extra == null ? null : extra.get("slotType"));
        return m;
    }
}
