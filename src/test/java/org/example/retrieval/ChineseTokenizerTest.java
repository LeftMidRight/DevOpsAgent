package org.example.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseTokenizerTest {

    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer();
    }

    @Test
    void keepsOpsAlertNameWhole() {
        List<String> tokens = tokenizer.tokenize("告警 HighCPUUsage 触发了");
        assertTrue(tokens.contains("highcpuusage"), tokens.toString());
    }

    @Test
    void splitsChineseSentence() {
        List<String> tokens = tokenizer.tokenize("CPU使用率过高怎么处理");
        assertTrue(tokens.contains("cpu"));
        assertTrue(tokens.contains("使用率"));
        assertTrue(tokens.contains("过高"));
        assertTrue(tokens.stream().noneMatch(t -> t.equals("怎么")));
    }

    @Test
    void indexAndQueryUseSameTokens() {
        String text = "HighMemoryUsage 内存使用率超过85%";
        assertEquals(tokenizer.tokenize(text), tokenizer.tokenize(text));
    }
}
