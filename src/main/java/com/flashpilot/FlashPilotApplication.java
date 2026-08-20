package com.flashpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FlashPilot —— 具备 AI 自治控制面的高并发秒杀系统。
 *
 * <p>整个系统分两个平面，这个划分是项目的核心论点：
 * <ul>
 *   <li><b>数据面</b>（{@code dataplane} 包）：请求路径，毫秒级，纯 Lua + 本地内存，没有任何 LLM 调用。</li>
 *   <li><b>控制面</b>（{@code controlplane} 包）：反馈回路，秒级，L0 规则控制器兜底 + L1 LLM Agent 做归因和提案。</li>
 * </ul>
 * LLM 一次调用是秒级，秒杀请求预算是毫秒级，差三个数量级，所以它只能待在本来就是秒级的控制面里。
 */
@org.mybatis.spring.annotation.MapperScan("com.flashpilot.**.mapper")
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlashPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashPilotApplication.class, args);
    }
}
