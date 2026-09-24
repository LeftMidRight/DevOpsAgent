package org.example.agent;

/**
 * 单轮模型输出的文本与推理过程。
 */
public record ModelReply(String answer, String reasoning) {

    public ModelReply {
        if (reasoning != null && reasoning.isBlank()) {
            reasoning = null;
        }
    }
}
