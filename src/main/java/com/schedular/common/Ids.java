package com.schedular.common;

import java.util.UUID;

/** 业务主键生成：前缀 + 32 位 UUID hex（去横线），见 01 架构 common/Ids。 */
public final class Ids {

    private Ids() {
    }

    public static String newTaskId() {
        return prefixed("T");
    }

    public static String newExecutionId() {
        return prefixed("E");
    }

    public static String newEventId() {
        return prefixed("V");
    }

    private static String prefixed(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
