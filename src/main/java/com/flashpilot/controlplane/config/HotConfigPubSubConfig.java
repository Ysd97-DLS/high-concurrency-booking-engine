package com.flashpilot.controlplane.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 订阅配置变更通道，收到消息立刻刷新本地缓存。
 *
 * <p>这条链路负责「快」，{@link HotConfigService#periodicReload()} 负责「稳」。
 * 两者都要，因为 Pub/Sub 不保证送达。
 */
@Configuration
public class HotConfigPubSubConfig {

    private static final Logger log = LoggerFactory.getLogger(HotConfigPubSubConfig.class);

    @Bean
    public RedisMessageListenerContainer hotConfigListenerContainer(RedisConnectionFactory connectionFactory,
                                                                    HotConfigService hotConfig) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                hotConfig.reloadFromRedis();
            } catch (Exception e) {
                log.warn("收到配置变更通知但刷新失败：{}", e.toString());
            }
        }, new ChannelTopic(HotConfigService.CHANNEL));
        return container;
    }
}
