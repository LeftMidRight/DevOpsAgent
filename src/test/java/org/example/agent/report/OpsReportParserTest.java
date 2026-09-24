package org.example.agent.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpsReportParserTest {

    private OpsReportParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpsReportParser();
    }

    @Test
    void parse_extractsJsonFromMarkdownFences() {
        String input = """
                分析结果如下：
                ```json
                {
                  "alertSummary": [
                    {"alertName": "HighCpu", "service": "payment", "severity": "CRITICAL", "duration": "10m", "status": "FIRING"}
                  ],
                  "findings": [
                    {
                      "alertName": "HighCpu",
                      "service": "payment",
                      "observation": "CPU 达 98%",
                      "hypothesis": "死循环",
                      "recommendation": "扩容并排查",
                      "evidenceIds": ["ev-1"]
                    }
                  ],
                  "unresolvedIssues": [],
                  "overallAssessment": "高风险",
                  "riskLevel": "HIGH"
                }
                ```
                """;

        OpsReportDraft draft = parser.parse(input);
        assertNotNull(draft);
        assertEquals(1, draft.alertSummary().size());
        assertEquals("HighCpu", draft.alertSummary().get(0).alertName());
        assertEquals(1, draft.findings().size());
        assertEquals("payment", draft.findings().get(0).service());
        assertEquals(1, draft.findings().get(0).evidenceIds().size());
        assertEquals("ev-1", draft.findings().get(0).evidenceIds().get(0));
        assertEquals("HIGH", draft.riskLevel());
    }

    @Test
    void parse_extractsDirectJsonObject() {
        String input = """
                {"alertSummary":[],"findings":[],"unresolvedIssues":["日志不可用"],"overallAssessment":"无法确认","riskLevel":"LOW"}
                """;

        OpsReportDraft draft = parser.parse(input);
        assertNotNull(draft);
        assertTrue(draft.alertSummary().isEmpty());
        assertEquals(1, draft.unresolvedIssues().size());
        assertEquals("LOW", draft.riskLevel());
    }

    @Test
    void parse_throwsWhenEmptyOrNoJson() {
        assertThrows(OpsReportParser.OpsReportParseException.class, () -> parser.parse(null));
        assertThrows(OpsReportParser.OpsReportParseException.class, () -> parser.parse("   "));
        assertThrows(OpsReportParser.OpsReportParseException.class, () -> parser.parse("没有任何 JSON 内容"));
    }

    @Test
    void parse_rejectsJsonWithoutReportContractFields() {
        assertThrows(OpsReportParser.OpsReportParseException.class, () -> parser.parse("{\"foo\":1}"));
        assertThrows(OpsReportParser.OpsReportParseException.class, () -> parser.parse("{}"));
    }
}
