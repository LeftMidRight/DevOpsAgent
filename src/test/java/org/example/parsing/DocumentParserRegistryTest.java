package org.example.parsing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentParserRegistryTest {

    private final DocumentParserRegistry registry =
            new DocumentParserRegistry(List.of(new PlainTextDocumentParser()));

    @Test
    void routesMarkdownToPlainTextParser() {
        UUID id = UUID.randomUUID();
        DocumentSource source = new DocumentSource(
                id,
                "guide.md",
                "text/markdown",
                "documents/" + id + "/guide.md",
                "# Title\n\nbody".getBytes(StandardCharsets.UTF_8));
        ParsedDocument parsed = registry.parse(source);
        assertEquals(PlainTextDocumentParser.PARSER_NAME, parsed.parser());
        assertEquals(1, parsed.blocks().size());
    }

    @Test
    void rejectsUnsupportedExtension() {
        UUID id = UUID.randomUUID();
        DocumentSource source = new DocumentSource(
                id,
                "report.pdf",
                "application/pdf",
                "documents/" + id + "/report.pdf",
                new byte[] {1, 2, 3});
        assertThrows(DocumentParseException.class, () -> registry.parse(source));
    }
}
