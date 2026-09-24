package org.example.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingAnswerCollectorTest {

    @Test
    void keepsOnlyLastModelRound() {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        collector.onStreamingChunk("CHECKING;");
        collector.onModelFinished(null);
        collector.onStreamingChunk("FINAL");
        collector.onModelFinished(null);

        assertEquals("FINAL", collector.authoritativeAnswer());
        assertEquals("CHECKING;FINAL", collector.displayBuffer());
    }

    @Test
    void keepsOnlyLastReasoningRound() {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        collector.onReasoningChunk("think-1;");
        collector.onRoundBoundary();
        collector.onReasoningChunk("think-2");

        assertEquals("think-2", collector.authoritativeReasoning());
        assertEquals("think-1;think-2", collector.reasoningDisplayBuffer());
    }

    @Test
    void prefersFinishedMessageOverRoundBuffer() {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        collector.onStreamingChunk("partial");
        collector.onModelFinished("COMPLETE");

        assertEquals("COMPLETE", collector.authoritativeAnswer());
    }

    @Test
    void missingFinishedStateIsNotAllRoundsConcatenated() {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        collector.onStreamingChunk("CHECKING;");
        collector.onRoundBoundary();
        collector.onStreamingChunk("FINAL");

        assertEquals("FINAL", collector.authoritativeAnswer());
        assertEquals("CHECKING;FINAL", collector.displayBuffer());
    }
}
