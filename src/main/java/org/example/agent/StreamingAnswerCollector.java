package org.example.agent;

import org.springframework.ai.chat.messages.Message;

/**
 * 从 ReAct 流式输出中提取权威最终回答与推理过程：每一轮模型生成单独累计，
 * 只保留最后一轮文本，避免把工具调用前的说明拼进终态。
 */
public final class StreamingAnswerCollector {

    private final StringBuilder displayBuffer = new StringBuilder();
    private final StringBuilder currentRound = new StringBuilder();
    private String lastCompletedRound;

    private final StringBuilder reasoningDisplayBuffer = new StringBuilder();
    private final StringBuilder currentReasoningRound = new StringBuilder();
    private String lastCompletedReasoningRound;

    public void onStreamingChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        displayBuffer.append(chunk);
        currentRound.append(chunk);
    }

    public void onReasoningChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        reasoningDisplayBuffer.append(chunk);
        currentReasoningRound.append(chunk);
    }

    public void onModelFinished(String finishedText) {
        if (finishedText != null && !finishedText.isBlank()) {
            lastCompletedRound = finishedText;
        } else if (currentRound.length() > 0) {
            lastCompletedRound = currentRound.toString();
        }
        currentRound.setLength(0);
    }

    public void onReasoningFinished(Message message) {
        String finishedReasoning = ReasoningContentExtractor.extract(message);
        if (finishedReasoning != null) {
            lastCompletedReasoningRound = finishedReasoning;
            currentReasoningRound.setLength(0);
            return;
        }
        if (currentReasoningRound.length() > 0) {
            lastCompletedReasoningRound = currentReasoningRound.toString();
            currentReasoningRound.setLength(0);
        }
    }

    /** 工具调用、Hook 或其他非模型流式事件，标志着上一轮模型输出结束 */
    public void onRoundBoundary() {
        if (currentRound.length() > 0) {
            lastCompletedRound = currentRound.toString();
            currentRound.setLength(0);
        }
        if (currentReasoningRound.length() > 0) {
            lastCompletedReasoningRound = currentReasoningRound.toString();
            currentReasoningRound.setLength(0);
        }
    }

    /**
     * @return 最后一轮模型回答；完全没有模型文本时返回 null
     */
    public String authoritativeAnswer() {
        if (currentRound.length() > 0) {
            return currentRound.toString();
        }
        if (lastCompletedRound == null || lastCompletedRound.isBlank()) {
            return null;
        }
        return lastCompletedRound;
    }

    /**
     * @return 最后一轮模型推理过程；无推理内容时返回 null
     */
    public String authoritativeReasoning() {
        if (currentReasoningRound.length() > 0) {
            return currentReasoningRound.toString();
        }
        if (lastCompletedReasoningRound == null || lastCompletedReasoningRound.isBlank()) {
            return null;
        }
        return lastCompletedReasoningRound;
    }

    public String displayBuffer() {
        return displayBuffer.toString();
    }

    public String reasoningDisplayBuffer() {
        return reasoningDisplayBuffer.toString();
    }
}
