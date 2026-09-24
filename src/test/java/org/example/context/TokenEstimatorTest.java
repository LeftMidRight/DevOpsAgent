package org.example.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void estimatesChineseMoreConservativelyThanLatinCharacters() {
        int chinese = estimator.estimate("内存使用率持续超过百分之九十");
        int latin = estimator.estimate("memory usage stays above ninety pct");

        assertTrue(chinese >= 14);
        assertTrue(latin < 14);
    }

    @Test
    void truncatesToConfiguredBudget() {
        String result = estimator.truncate("这是一段需要因为上下文预算而截断的较长文本", 12);

        assertTrue(result.contains("摘要因上下文预算被截断"));
    }
}
