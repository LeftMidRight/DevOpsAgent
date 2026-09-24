package org.example.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunEvidenceAnalyzerTest {

    @Test
    void classify_emptyArrayIsEmpty() {
        assertEquals(EvidenceStatus.EMPTY, RunEvidenceAnalyzer.classify("[]"));
    }

    @Test
    void classify_malformedJsonIsError() {
        assertEquals(EvidenceStatus.ERROR, RunEvidenceAnalyzer.classify("{broken"));
        assertEquals(EvidenceStatus.ERROR, RunEvidenceAnalyzer.classify("[broken"));
    }

    @Test
    void classify_mcpErrorProtocolIsError() {
        String raw = "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"query failed\"}]}";
        assertEquals(EvidenceStatus.ERROR, RunEvidenceAnalyzer.classify(raw));
    }

    @Test
    void classify_plainTextRemainsSuccess() {
        assertEquals(EvidenceStatus.SUCCESS, RunEvidenceAnalyzer.classify("2026-09-16T14:00:00+08:00"));
    }

    @Test
    void classify_explicitFailureFields() {
        assertEquals(EvidenceStatus.ERROR, RunEvidenceAnalyzer.classify("{\"success\":false,\"error\":\"boom\"}"));
        assertEquals(EvidenceStatus.EMPTY, RunEvidenceAnalyzer.classify("{\"alerts\":[]}"));
    }

    @Test
    void slices_splitMultiAlertPayload() {
        String raw = """
                {"success":true,"alerts":[
                  {"alert_name":"HighCpu","service":"payment"},
                  {"alert_name":"HighMem","service":"order"}
                ]}
                """;
        List<RunEvidenceAnalyzer.EvidenceSlice> slices = RunEvidenceAnalyzer.slices(raw);
        assertEquals(2, slices.size());
        assertEquals("payment", slices.get(0).service());
        assertEquals("HighCpu", slices.get(0).alertName());
        assertEquals("order", slices.get(1).service());
        assertEquals("HighMem", slices.get(1).alertName());
    }

    @Test
    void normalizeArguments_canonicalizesJsonKeyOrder() {
        String a = RunEvidenceAnalyzer.normalizeArguments("{\"b\":1,\"a\":2}");
        String b = RunEvidenceAnalyzer.normalizeArguments("{ \"a\" : 2, \"b\" : 1 }");
        assertEquals(a, b);
    }
}
