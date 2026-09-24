package org.example.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 统一业务 Agent 配置。模型名必须显式指定，
 * 不再回退到 DashScopeChatModel.DEFAULT_MODEL_NAME。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 业务模型名称（火山方舟 endpoint / 模型 ID） */
    private String model = "doubao-seed-2.1-turbo";

    private Chat chat = new Chat();
    private Ops ops = new Ops();
    private Budget budget = new Budget();
    private Thinking thinking = new Thinking();

    @Getter
    @Setter
    public static class Thinking {
        /** 是否向模型请求返回思考过程（豆包 thinking 参数） */
        private boolean enabled = true;
        /** enabled / disabled / auto，见火山方舟文档 */
        private String type = "enabled";
    }

    @Getter
    @Setter
    public static class Chat {
        private double temperature = 0.7;
        private int maxTokens = 2000;
        private double topP = 0.9;
    }

    @Getter
    @Setter
    public static class Ops {
        private double temperature = 0.3;
        private int maxTokens = 8000;
        private double topP = 0.9;
    }

    /**
     * 单轮任务的运行限制。默认值待小规模验证后调整，
     * 不视为已测得的最优参数。
     */
    @Getter
    @Setter
    public static class Budget {
        /** 单轮最大模型调用次数（含 ReAct 循环每一轮与一次报告修正） */
        private int maxModelCalls = 12;
        /** 单轮最大工具调用次数；一次模型回复发出多个调用逐个计数 */
        private int maxToolCalls = 20;
        /** 单轮总耗时上限（秒） */
        private long maxDurationSeconds = 240;
        /** 返回给模型的单个工具结果最大字符数（超出受控截断） */
        private int maxToolResultChars = 6000;
        /** 同一工具及相同参数连续失败/空结果上限，达到后拒绝重复调用 */
        private int maxConsecutiveToolFailures = 2;
        /** 证据记录保存的内容最大字符数 */
        private int maxEvidenceChars = 4000;
    }
}
