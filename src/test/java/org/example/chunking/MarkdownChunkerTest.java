package org.example.chunking;

import org.example.config.DocumentChunkConfig;
import org.example.context.TokenEstimator;
import org.example.service.ChunkDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownChunkerTest {

    @Test
    void splitsLongParagraphAndRepeatsTableHeader() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setTargetTokens(30);
        config.setMaxTokens(40);
        config.setOverlapTokens(8);
        MarkdownChunker chunker = new MarkdownChunker(new TokenEstimator(), config);
        MarkdownDocument document = new MarkdownParser().parse("""
                # MySQL
                检查连接池大小。检查连接池大小。检查连接池大小。检查连接池大小。检查连接池大小。检查连接池大小。检查连接池大小。检查连接池大小。

                | 列 | 值 |
                | --- | --- |
                | 一 | A |
                | 二 | B |
                | 三 | C |
                | 四 | D |
                | 五 | E |
                | 六 | F |
                | 七 | G |
                | 八 | H |
                | 九 | I |
                | 十 | J |
                | 十一 | K |
                | 十二 | L |

                ```bash
                echo 1
                echo 2
                echo 3
                echo 4
                echo 5
                echo 6
                ```

                1. 第一步
                2. 第二步
                """);
        List<ChunkDraft> chunks = chunker.chunk(document, "mysql.md");
        assertTrue(chunks.stream().filter(chunk -> "paragraph".equals(chunk.contentType())).count() > 1);
        List<ChunkDraft> tables = chunks.stream().filter(chunk -> "table".equals(chunk.contentType())).toList();
        assertTrue(tables.size() > 1);
        assertTrue(tables.stream().allMatch(chunk -> chunk.content().startsWith("| 列 | 值 |")));
        assertTrue(chunks.stream().anyMatch(chunk -> "code".equals(chunk.contentType()) && chunk.content().contains("```bash")));
        String lists = chunks.stream().filter(chunk -> "list".equals(chunk.contentType()))
                .map(ChunkDraft::content).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(lists.indexOf("第一步") < lists.indexOf("第二步"));
        assertTrue(chunks.get(0).retrievalText().contains("[Section] MySQL"));
        assertEquals("MySQL", chunks.get(0).title());
    }

    @Test
    void oversizedUnitsStayWithinMaxTokens() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setTargetTokens(20);
        config.setMaxTokens(24);
        config.setOverlapTokens(0);
        TokenEstimator estimator = new TokenEstimator();
        MarkdownChunker chunker = new MarkdownChunker(estimator, config);
        String longCell = "超长单元格".repeat(30);
        String longCode = "x".repeat(200);
        String longItem = "这一步要检查连接池、慢查询、锁等待和下游超时。".repeat(8);
        MarkdownDocument document = new MarkdownParser().parse("""
                | 列 | 值 |
                | --- | --- |
                | 名称 | %s |

                ```json
                %s
                ```

                - %s
                """.formatted(longCell, longCode, longItem));
        List<ChunkDraft> chunks = chunker.chunk(document, "limits.md");
        assertTrue(chunks.stream()
                .filter(chunk -> "table".equals(chunk.contentType()))
                .allMatch(chunk -> chunk.content().startsWith("| 列 | 值 |\n| --- | --- |")));
        assertTrue(chunks.stream().anyMatch(chunk -> "code".equals(chunk.contentType())));
        assertTrue(chunks.stream().anyMatch(chunk -> "list".equals(chunk.contentType())));
        assertTrue(chunks.stream().allMatch(chunk -> estimator.estimate(chunk.content()) <= config.getMaxTokens()));
    }

    @Test
    void sameSentenceUnderDifferentHeadingsStaysDistinct() {
        MarkdownChunker chunker = new MarkdownChunker(new TokenEstimator(), new DocumentChunkConfig());
        MarkdownDocument document = new MarkdownParser().parse("""
                # MySQL
                检查连接池大小
                # Redis
                检查连接池大小
                # HTTP Client
                检查连接池大小
                """);
        List<ChunkDraft> chunks = chunker.chunk(document, "pools.md");
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).retrievalText().contains("[Section] MySQL"));
        assertTrue(chunks.get(1).retrievalText().contains("[Section] Redis"));
        assertTrue(chunks.get(2).retrievalText().contains("[Section] HTTP Client"));
    }

    @Test
    void keepsMineruHtmlTableTogether() throws Exception {
        MarkdownChunker chunker = new MarkdownChunker(new TokenEstimator(), new DocumentChunkConfig());
        String markdown = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/mineru/xlsx.md"));
        List<ChunkDraft> chunks = chunker.chunk(new MarkdownParser().parse(markdown), "cpu-sheet.xlsx");
        List<ChunkDraft> tables = chunks.stream().filter(chunk -> "table".equals(chunk.contentType())).toList();
        assertEquals(1, tables.size());
        assertTrue(tables.get(0).content().contains("web-1"));
        assertTrue(tables.get(0).content().contains("空闲"));
        assertTrue(tables.get(0).content().contains("<p></p>"));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("备用")));
    }
}
