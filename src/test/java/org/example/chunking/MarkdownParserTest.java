package org.example.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void keepsNestedHeadingPath() {
        MarkdownDocument document = parser.parse("""
                # Kubernetes
                ## Pod 故障
                ### OOMKilled
                查看 memory limit。
                """);
        MarkdownElement body = document.elements().get(3);
        assertEquals(MarkdownElementType.PARAGRAPH, body.type());
        assertEquals(List.of("Kubernetes", "Pod 故障", "OOMKilled"), body.headingPath());
    }

    @Test
    void keepsTableCodeAndListSeparate() {
        MarkdownDocument document = parser.parse("""
                # Runbook
                | 告警 | 阈值 |
                | --- | --- |
                | CPU | 80% |

                ```bash
                kubectl describe pod
                ```

                1. 先看事件
                2. 再看限制
                """);
        assertEquals(MarkdownElementType.TABLE, document.elements().get(1).type());
        assertEquals(MarkdownElementType.CODE, document.elements().get(2).type());
        assertEquals("bash", document.elements().get(2).metadata().get("language"));
        assertTrue(document.elements().get(2).content().contains("kubectl describe pod"));
        MarkdownElement list = document.elements().get(3);
        assertEquals(MarkdownElementType.LIST, list.type());
        assertTrue(list.content().indexOf("先看事件") < list.content().indexOf("再看限制"));
    }

    @Test
    void closingFenceMustMatchOpeningMarker() {
        MarkdownDocument document = parser.parse("""
                ```bash
                echo ok
                ~~~
                still code
                ```
                """);
        assertEquals(1, document.elements().size());
        assertEquals(MarkdownElementType.CODE, document.elements().get(0).type());
        assertTrue(document.elements().get(0).content().contains("still code"));
    }

    @Test
    void parsesSavedMineruPdfMarkdown() throws Exception {
        MarkdownDocument document = parseSaved("pdf.md");
        assertTrue(document.elements().stream().anyMatch(element -> element.type() == MarkdownElementType.HEADING));
        assertTrue(document.elements().stream().anyMatch(element -> element.type() == MarkdownElementType.PARAGRAPH));
        assertTrue(document.elements().stream().anyMatch(element -> element.type() == MarkdownElementType.LIST));
    }

    @Test
    void parsesSavedMineruDocxMarkdown() throws Exception {
        MarkdownDocument document = parseSaved("docx.md");
        assertTrue(has(document, MarkdownElementType.HEADING, "磁盘使用率排查"));
        assertTrue(has(document, MarkdownElementType.PARAGRAPH, "根分区"));
        assertTrue(has(document, MarkdownElementType.LIST, "df -h"));
        assertTrue(has(document, MarkdownElementType.LIST, "du -sh"));
        assertTrue(has(document, MarkdownElementType.TABLE, "挂载点"));
        assertTrue(has(document, MarkdownElementType.TABLE, "/var"));
    }

    @Test
    void parsesSavedMineruPptxMarkdown() throws Exception {
        MarkdownDocument document = parseSaved("pptx.md");
        String text = document.elements().stream().map(MarkdownElement::content).reduce("", (a, b) -> a + "\n" + b);
        int firstPage = text.indexOf("内存告警处理");
        int secondPage = text.indexOf("第二页：回收缓存");
        assertTrue(firstPage >= 0 && secondPage > firstPage);
        assertEquals(2, document.elements().stream().filter(element -> element.type() == MarkdownElementType.LIST).count());
        assertTrue(has(document, MarkdownElementType.LIST, "free -m"));
        assertTrue(has(document, MarkdownElementType.LIST, "drop_caches")
                || has(document, MarkdownElementType.LIST, "drop\\_caches"));
    }

    @Test
    void parsesSavedMineruXlsxMarkdown() throws Exception {
        MarkdownDocument document = parseSaved("xlsx.md");
        assertTrue(has(document, MarkdownElementType.HEADING, "CPU"));
        assertTrue(has(document, MarkdownElementType.HEADING, "空表"));
        assertTrue(has(document, MarkdownElementType.TABLE, "web-1"));
        assertTrue(has(document, MarkdownElementType.TABLE, "空闲"));
        assertTrue(has(document, MarkdownElementType.TABLE, "<p></p>")
                || has(document, MarkdownElementType.TABLE, "<td></td>"));
        assertTrue(has(document, MarkdownElementType.PARAGRAPH, "备用"));
    }

    @Test
    void parsesSavedMineruImageOcrMarkdown() throws Exception {
        MarkdownDocument document = parseSaved("image-ocr.md");
        assertTrue(has(document, MarkdownElementType.HEADING, "OCR 告警文本"));
        assertTrue(has(document, MarkdownElementType.PARAGRAPH, "主机web-1"));
        assertTrue(has(document, MarkdownElementType.TABLE, "挂载点"));
        assertTrue(has(document, MarkdownElementType.PARAGRAPH, "噪声"));
    }

    private static MarkdownDocument parseSaved(String name) throws Exception {
        String markdown = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/mineru").resolve(name));
        return new MarkdownParser().parse(markdown);
    }

    private static boolean has(MarkdownDocument document, MarkdownElementType type, String text) {
        return document.elements().stream()
                .anyMatch(element -> element.type() == type && element.content().contains(text));
    }
}
