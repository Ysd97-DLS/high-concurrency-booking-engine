package com.flashpilot.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 实例身份。租约表以它为 key 记录「这个实例手里还攥着多少库存」，
 * 所以它必须在实例的整个生命周期内稳定，且多实例之间不能重复。
 *
 * <p>多实例压测时建议显式指定 {@code INSTANCE_ID=app-1}，排查问题时日志好认。
 */
@Component
public class InstanceIdentity {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdentity.class);

    private final String id;

    public InstanceIdentity(FlashPilotProperties props) {
        String configured = props.instanceId();
        if (configured != null && !configured.isBlank()) {
            this.id = configured.trim();
        } else {
            this.id = generate();
        }
        log.info("实例身份 instanceId={}", this.id);
    }

    private static String generate() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        long pid = ProcessHandle.current().pid();
        int salt = ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF);
        return host + "-" + pid + "-" + Integer.toHexString(salt);
    }

    public String id() {
        return id;
    }
}
