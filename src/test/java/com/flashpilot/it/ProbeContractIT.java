package com.flashpilot.it;

import java.util.List;
import java.util.Map;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /probe/*} 的契约测试 —— 这批断言的对面是<b>另一个仓库</b>。
 *
 * <h2>为什么这个测试值得存在</h2>
 *
 * 外部的压测编排工具在它自己的场景文件里写着这样的等式：
 * <pre>initialStock == bucketSum + leaseHeld + orderCount + unprocessed</pre>
 * 那些名字是<b>跨仓库的接口</b>。在这里把一个字段改名或删掉，编译不会报错、
 * 本仓库的测试不会红，而对面那个工具会在跑到第十五秒时才失败 ——
 * 报的还是「判据引用了探针没有返回的量」，要翻两个仓库才能定位。
 *
 * <p>所以这批断言钉的不是「接口能不能调通」，而是<b>那几个名字还在不在</b>。
 *
 * <h2>它同时钉住一个设计决定</h2>
 *
 * 探针只给<b>原始的量</b>，不给派生的结论（有没有超卖、等式过没过）。
 * 把结论一起递出去，外部工具会忍不住直接用 {@code passed == 1} 当判据，
 * 于是绕一圈又回到「系统自己判自己」—— 而外部校验的全部价值就在于它<b>不是</b>自己。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("契约：外部压测编排工具依赖的探针字段")
class ProbeContractIT extends IntegrationBase {

    /**
     * 场景文件里出现过的每一个量。
     * <b>改动这个清单之前，先去改对面仓库的 scenarios/*.yml</b>，否则那边会在运行时才炸。
     */
    private static final List<String> REQUIRED = List.of(
            // 号源在谁手里 —— 号源守恒等式的四项
            "initialStock", "bucketSum", "leaseHeld", "orderCount", "unprocessed",
            // 消息链路 —— 链路守恒等式的五项
            "streamLength", "consumed", "duplicate", "oversoldBlocked", "deadLetter",
            // 采样本身可不可信
            "stableSample");

    /** 派生结论，刻意<b>不</b>暴露。 */
    private static final List<String> MUST_NOT_EXPOSE = List.of(
            "oversold", "undersold", "vanished", "passed", "soldOut", "equations");

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("场景文件引用的每个量都在，且都是数")
    void everyReferencedQuantityIsPresentAndNumeric() {
        Map<String, Object> body = given().get("/probe/invariants")
                .then().statusCode(200)
                .extract().body().jsonPath().getMap("$");

        assertThat(body.keySet())
                .as("外部场景文件引用的量必须全部存在 —— 缺一个，对面就会在跑到一半时失败")
                .containsAll(REQUIRED);

        for (String key : REQUIRED) {
            assertThat(body.get(key))
                    .as("%s 必须是数：契约是「扁平的命名数字」，字符串会被对面直接跳过，"
                            + "于是判据引用它时报「探针没有返回这个量」", key)
                    .isInstanceOf(Number.class);
        }
    }

    @Test
    @DisplayName("不暴露派生结论 —— 外部校验的价值就在于它不是系统自己")
    void derivedVerdictsAreNotExposed() {
        Map<String, Object> body = given().get("/probe/invariants")
                .then().statusCode(200)
                .extract().body().jsonPath().getMap("$");

        assertThat(body.keySet())
                .as("探针只给原料。给了结论，外部工具就会拿 passed == 1 当判据，"
                        + "绕一圈回到系统自己判自己")
                .doesNotContainAnyElementsOf(MUST_NOT_EXPOSE);
    }

    @Test
    @DisplayName("重置之后号源全部在桶里 —— 这是每一轮实验的起点")
    void resetPutsEveryLotBackInTheBuckets() {
        given().queryParam("poolId", 1001)
                .queryParam("totalStock", 500)
                .queryParam("buckets", 4)
                .post("/probe/reset")
                .then().statusCode(200).body("ok", org.hamcrest.Matchers.is(true));

        Map<String, Object> body = given().get("/probe/invariants")
                .then().statusCode(200)
                .extract().body().jsonPath().getMap("$");

        int initial = ((Number) body.get("initialStock")).intValue();
        int bucketSum = ((Number) body.get("bucketSum")).intValue();
        int leaseHeld = ((Number) body.get("leaseHeld")).intValue();
        int orderCount = ((Number) body.get("orderCount")).intValue();
        long unprocessed = ((Number) body.get("unprocessed")).longValue();

        assertThat(initial).isEqualTo(500);
        // 号源守恒等式在起点上也必须成立 —— 一个连起点都不平的系统，
        // 压测之后的残差根本无法归因。
        assertThat(bucketSum + leaseHeld + orderCount + unprocessed)
                .as("重置后的号源守恒：%d(桶) + %d(实例持有) + %d(占号) + %d(未落库)",
                        bucketSum, leaseHeld, orderCount, unprocessed)
                .isEqualTo(initial);
        assertThat(orderCount).isZero();
    }
}
