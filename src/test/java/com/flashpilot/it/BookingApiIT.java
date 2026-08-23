package com.flashpilot.it;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * 接口自动化：挂号主流程、状态机、越权面，全部走真实 HTTP。
 *
 * <h2>为什么是 RestAssured 而不是 MockMvc</h2>
 *
 * 这批用例里有一半验的是<b>协议层</b>的行为，而 MockMvc 根本不经过那一层：
 * 没有令牌时返回的是 401 还是 500（{@code ApiExceptionHandler} 的存在理由）、
 * 拿别人的凭证号退号时响应体里<b>回不回显那张单的状态</b>（修漏洞时最容易留下的尾巴）、
 * {@code patientId=0} 是 400 还是 500。这些只有真的发一个 HTTP 请求才算数。
 *
 * <h2>断言的是不变量，不是「能不能调通」</h2>
 *
 * 单条接口通不通没什么信息量。这里每一条都对应一个<b>出过事或可能出事</b>的判断：
 * <ul>
 *   <li>一人一号（{@code uk_active} 的物理保证是否真的暴露成了业务码 4002）</li>
 *   <li>售罄是 200 + 业务码而不是 5xx（否则压测的错误率被业务终局污染）</li>
 *   <li>退号后号源回到池里，且五条一致性等式仍然全平</li>
 *   <li>凭证号可枚举，所以「不是你的」必须和「不存在」不可区分</li>
 * </ul>
 *
 * <h2>异步落库怎么断言</h2>
 *
 * 抢号成功只表示号从 Redis 扣走并写进了 Stream，预约单由消费者异步落 MySQL。
 * 所以凡是要看单据的地方都用 Awaitility 轮询而不是 {@code Thread.sleep} ——
 * 固定睡眠要么不够（偶发红）要么过长（每条用例都在等），两种都会让人开始忽略这批测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("接口自动化：挂号主流程与越权面")
class BookingApiIT extends IntegrationBase {

    /** 号池 = 排班 id。用 99xxxx 段，和演示数据、压测号池都不重叠。 */
    private static final long POOL = 990101L;
    private static final long DEPT = 990001L;
    private static final long DOCTOR = 990001L;
    private static final long PATIENT_A = 990001L;
    private static final long PATIENT_B = 990002L;
    private static final long PATIENT_C = 990003L;

    private static final Duration CONSUMER_CATCHUP = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /** 令牌在整个类里复用：{@code /clinic/identify} 每 IP 每分钟只签 20 个，
     *  每条用例都重签会把窗口用光，届时失败原因是限流而不是被测行为。 */
    private static String tokenA;
    private static String tokenB;
    private static String tokenC;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        seedFixtures();
        resetPool(50);
        if (tokenA == null) {
            tokenA = issueToken(PATIENT_A);
            tokenB = issueToken(PATIENT_B);
            tokenC = issueToken(PATIENT_C);
        }
    }

    // ── 主流程 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("主流程")
    class HappyPath {

        @Test
        @DisplayName("抢号 → 单据落库 → 支付 → 状态变为 BOOKED")
        void bookThenPay() {
            grab(tokenA, "dev-a").body("code", equalTo(200));

            String apptNo = awaitFirstApptNo(tokenA);
            assertThat(apptNo).startsWith("A" + POOL + "-");

            as(tokenA).get("/clinic/appointments/{no}", apptNo)
                    .then().statusCode(200)
                    .body("status", equalTo("PENDING_PAY"));

            as(tokenA).post("/clinic/appointments/{no}/pay", apptNo)
                    .then().statusCode(200)
                    .body("ok", is(true))
                    .body("status", equalTo("BOOKED"));
        }

        @Test
        @DisplayName("未支付的单不能退：状态机只允许 BOOKED → REFUNDED")
        void cannotRefundBeforePaying() {
            grab(tokenC, "dev-c").body("code", equalTo(200));
            String apptNo = awaitFirstApptNo(tokenC);

            // PENDING_PAY 的单走的是超时释放那条路，不是退号 ——
            // 两条路都会归还号源，混用会造成双重归还，而双重归还就是超卖。
            as(tokenC).post("/clinic/appointments/{no}/refund", apptNo)
                    .then().statusCode(200)
                    .body("ok", is(false))
                    .body("result", equalTo("WRONG_STATE"))
                    .body("status", equalTo("PENDING_PAY"));
        }

        @Test
        @DisplayName("支付后退号，号源回到池里，且五条一致性等式仍然全平")
        void refundReturnsSlotAndKeepsInvariants() {
            grab(tokenB, "dev-b").body("code", equalTo(200));
            String apptNo = awaitFirstApptNo(tokenB);

            as(tokenB).post("/clinic/appointments/{no}/pay", apptNo)
                    .then().statusCode(200).body("status", equalTo("BOOKED"));

            as(tokenB).post("/clinic/appointments/{no}/refund", apptNo)
                    .then().statusCode(200)
                    .body("ok", is(true))
                    .body("status", equalTo("REFUNDED"));

            // 退号也是异步归还，等消费追平再校验，否则校验的是一个正在变化的系统。
            await().atMost(CONSUMER_CATCHUP).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> given().get("/verify/check").then()
                            .statusCode(200)
                            .body("oversold", equalTo(0))
                            .body("undersold", equalTo(0))
                            .body("vanished", equalTo(0))
                            .body("passed", is(true)));
        }

        @Test
        @DisplayName("排班查得到：科室 + 日期能列出刚建的号池")
        void schedulesAreListable() {
            given().queryParam("departmentId", DEPT)
                    .queryParam("date", LocalDate.now().plusDays(1).toString())
                    .get("/clinic/schedules")
                    .then().statusCode(200)
                    .body("size()", greaterThanOrEqualTo(1))
                    // 字段名是 scheduleId 不是 id：号池 id 即排班 id，
                    // 而接口对外一直用 scheduleId 这个名字（ScheduleMapper.xml:39）。
                    .body("find { it.scheduleId == " + POOL + " }", notNullValue())
                    .body("find { it.scheduleId == " + POOL + " }.status", equalTo("OPEN"));
        }
    }

    // ── 业务终局：必须是 200 + 业务码，不能是 5xx ───────────────────────

    @Nested
    @DisplayName("业务终局的表达方式")
    class BusinessOutcomes {

        @Test
        @DisplayName("一人一号：同一患者重复抢同一号池返回 4002，而不是第二张单")
        void oneSlotPerPatient() {
            grab(tokenA, "dev-a").body("code", equalTo(200));
            awaitFirstApptNo(tokenA);                       // 等第一张单真的落库

            grab(tokenA, "dev-a").body("code", equalTo(4002));

            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_appointment WHERE schedule_id=? AND patient_id=?"
                            + " AND status IN ('PENDING_PAY','BOOKED')",
                    Integer.class, POOL, PATIENT_A);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("售罄返回 200 + 4001：4xx/5xx 会把压测的错误率污染成业务终局")
        void soldOutIsNotAnError() {
            resetPool(1);                                    // 池里只放一个号

            grab(tokenA, "dev-a").body("code", equalTo(200));
            grab(tokenB, "dev-b")
                    .statusCode(200)                         // ← 关键：HTTP 层必须是 200
                    .body("code", equalTo(4001));
        }
    }

    // ── 身份与越权 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("身份与越权")
    class AuthAndOwnership {

        @Test
        @DisplayName("无令牌访问患者接口 → 401（不是 500，也不是 200）")
        void missingTokenIsUnauthorized() {
            given().get("/clinic/appointments")
                    .then().statusCode(401)
                    .body("error", equalTo("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("令牌被篡改一个字符即失效 —— 身份不能由客户端自报")
        void tamperedTokenIsRejected() {
            String tampered = flipLastChar(tokenA);
            given().header("X-Patient-Token", tampered)
                    .get("/clinic/appointments")
                    .then().statusCode(401);
        }

        @Test
        @DisplayName("凭证号可枚举，所以别人的单必须和不存在的单不可区分")
        void othersApptLooksLikeNotFound() {
            grab(tokenA, "dev-a").body("code", equalTo(200));
            String apptNoOfA = awaitFirstApptNo(tokenA);

            // B 拿着 A 的凭证号查详情：只能得到 found=false，
            // 不能泄露就诊日期 / 医生 / 科室 —— 那是攻击链的第一环。
            as(tokenB).get("/clinic/appointments/{no}", apptNoOfA)
                    .then().statusCode(200)
                    .body("found", is(false))
                    // 断言的是「根本没有这个键」，不是「这个键为 null」——
                    // 后者在 GPath 里对缺失键同样成立，区分不出回显路径漏没漏。
                    .body("containsKey('status')", is(false));
        }

        @Test
        @DisplayName("退别人的号被拒，且响应体不回显那张单的状态（修漏洞最容易留的尾巴）")
        void cannotRefundSomeoneElsesAppointment() {
            grab(tokenA, "dev-a").body("code", equalTo(200));
            String apptNoOfA = awaitFirstApptNo(tokenA);

            as(tokenB).post("/clinic/appointments/{no}/refund", apptNoOfA)
                    .then().statusCode(200)
                    .body("ok", is(false))
                    .body("result", equalTo("NOT_FOUND"))
                    // 主路径挡住了不等于挡住了：回显路径漏了就成了状态查询接口。
                    .body("containsKey('status')", is(false));

            // A 的单必须原封不动。
            as(tokenA).get("/clinic/appointments/{no}", apptNoOfA)
                    .then().body("status", equalTo("PENDING_PAY"));
        }
    }

    // ── 输入校验 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("输入校验")
    class InputValidation {

        @Test
        @DisplayName("patientId 非正 → 400，不是 500 + 堆栈")
        void nonPositivePatientIdIsBadRequest() {
            given().queryParam("patientId", 0)
                    .post("/clinic/identify")
                    .then().statusCode(400)
                    .body("error", equalTo("BAD_REQUEST"));
        }

        @Test
        @DisplayName("日期格式不合法 → 400，不是 500")
        void malformedDateIsBadRequest() {
            given().queryParam("departmentId", DEPT)
                    .queryParam("date", "garbage")
                    .get("/clinic/schedules")
                    .then().statusCode(400)
                    .body("error", equalTo("BAD_REQUEST"));
        }
    }

    // ── 夹具与工具 ──────────────────────────────────────────────────────

    /** 建科室 / 医生 / 排班 / 患者。集成底座刻意不灌演示数据，所以自己造。 */
    private void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO t_department (id, code, name) VALUES (?,?,?)",
                DEPT, "IT-TEST", "接口测试科");
        jdbc.update("INSERT IGNORE INTO t_doctor (id, department_id, name, title) VALUES (?,?,?,?)",
                DOCTOR, DEPT, "接口测试医生", "ATTENDING");
        jdbc.update("""
                INSERT IGNORE INTO t_schedule
                    (id, hospital_id, doctor_id, department_id, visit_date, period, slot_type,
                     fee_cents, total_slots, booked_slots, released_slots,
                     release_at, visit_start, visit_end, status)
                VALUES (?, 1, ?, ?, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'AM', 'NORMAL',
                        1000, 50, 0, 50,
                        DATE_SUB(NOW(), INTERVAL 1 HOUR), '08:30:00', '11:30:00', 'OPEN')
                """, POOL, DOCTOR, DEPT);
        for (long p : new long[] { PATIENT_A, PATIENT_B, PATIENT_C }) {
            jdbc.update("INSERT IGNORE INTO t_patient (id, id_card, name, phone) VALUES (?,?,?,?)",
                    p, "IT" + p, "测试患者" + p, "1380000" + p);
        }
        // 上一条用例可能把患者拉黑或攒了失约次数，逐条清干净 ——
        // 用例之间互相影响是这类测试最常见的偶发红。
        jdbc.update("UPDATE t_patient SET no_show_count=0, blocked_until=NULL WHERE id IN (?,?,?)",
                PATIENT_A, PATIENT_B, PATIENT_C);
    }

    /** 清空该号池的单据与 Redis 库存，把风控 / 限流 / 热参数一并复位。 */
    private void resetPool(int totalStock) {
        given().queryParam("poolId", POOL)
                .queryParam("totalStock", totalStock)
                .queryParam("buckets", 4)
                .post("/verify/preheat")
                .then().statusCode(200).body("ok", is(true));
        jdbc.update("UPDATE t_schedule SET total_slots=?, released_slots=? WHERE id=?",
                totalStock, totalStock, POOL);
    }

    private String issueToken(long patientId) {
        return given().queryParam("patientId", patientId)
                .post("/clinic/identify")
                .then().statusCode(200)
                .extract().path("token");
    }

    private io.restassured.response.ValidatableResponse grab(String token, String deviceId) {
        return as(token).queryParam("deviceId", deviceId)
                .post("/seckill/{poolId}", POOL)
                .then().statusCode(200);
    }

    /** 抢号成功后，等消费者把预约单落进 MySQL，返回凭证号。 */
    private String awaitFirstApptNo(String token) {
        await().atMost(CONSUMER_CATCHUP).pollInterval(Duration.ofMillis(200))
                .until(() -> as(token).get("/clinic/appointments")
                        .then().statusCode(200)
                        .extract().path("size()"), size -> (Integer) size >= 1);
        return as(token).get("/clinic/appointments")
                .then().extract().path("[0].apptNo");
    }

    private RequestSpecification as(String token) {
        return given().header("X-Patient-Token", token).contentType(ContentType.JSON);
    }

    private static String flipLastChar(String token) {
        char last = token.charAt(token.length() - 1);
        char other = last == 'A' ? 'B' : 'A';
        return token.substring(0, token.length() - 1) + other;
    }
}
