package org.example.parsing.mineru;

import org.example.parsing.BlockType;
import org.example.parsing.ParsedBlock;
import org.example.parsing.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerUStructuredContentMapperTest {

    @Test
    void mapsPagesAndBlocks() {
        String json = """
                {
                  "pages": [
                    {
                      "page_idx": 0,
                      "blocks": [
                        {"type": "text", "content": "Hello MinerU"},
                        {"type": "table", "content": "A | B"}
                      ]
                    }
                  ],
                  "metadata": {
                    "producer": {"name": "mineru", "version": "4.0.0"}
                  }
                }
                """;
        UUID documentId = UUID.randomUUID();
        ParsedDocument document = MinerUStructuredContentMapper.map(documentId, "sample.html", json);
        assertEquals(MinerUStructuredContentMapper.PARSER_NAME, document.parser());
        assertEquals("4.0.0", document.parserVersion());
        assertEquals(2, document.blocks().size());
        ParsedBlock first = document.blocks().get(0);
        assertEquals(BlockType.TEXT, first.type());
        assertEquals("Hello MinerU", first.content());
        assertEquals(1, first.pageNumber());
        ParsedBlock table = document.blocks().get(1);
        assertEquals(BlockType.TABLE, table.type());
        assertTrue(table.content().contains("A | B"));
    }
}
