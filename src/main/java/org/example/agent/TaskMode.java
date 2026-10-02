package org.example.agent;

import java.util.Locale;

/**
 * 统一业务 Agent 的任务模式。模式只决定任务要求、输出约定与执行预算，
 * 不对应不同的 Agent 角色定义。
 */
public enum TaskMode {
    CHAT;

    /**
     * 大小写归一化解析；null/空白缺省为 CHAT；未知值抛出参数异常。
     */
    public static TaskMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT;
        }
        if ("CHAT".equals(raw.trim().toUpperCase(Locale.ROOT))) {
            return CHAT;
        }
        throw new IllegalArgumentException("未知的任务模式: " + raw + "，仅支持 CHAT");
    }
}
