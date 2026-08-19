package com.flashpilot.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lua 脚本返回值的取值工具。
 *
 * <p>为什么需要它：Lua 的 {@code return {1, "1699..."}} 到了 Java 侧是个混合类型的 List，
 * 元素可能是 Long、String，也可能是还没反序列化的 byte[]（取决于客户端和序列化器的组合）。
 * 与其在每个调用点写 instanceof，不如收敛到这里。
 */
public final class Replies {

    private Replies() {
    }

    public static long asLong(List<?> reply, int index) {
        return asLong(reply == null || reply.size() <= index ? null : reply.get(index));
    }

    public static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = asString(value);
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static int asInt(List<?> reply, int index) {
        return (int) asLong(reply, index);
    }

    public static String asString(List<?> reply, int index) {
        return asString(reply == null || reply.size() <= index ? null : reply.get(index));
    }

    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
