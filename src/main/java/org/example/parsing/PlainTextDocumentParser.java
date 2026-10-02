package org.example.parsing;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class PlainTextDocumentParser implements DocumentParser {

    public static final String PARSER_NAME = "plain-text";
    public static final String PARSER_VERSION = "1";

    @Override
    public boolean supports(DocumentSource source) {
        return DocumentExtensionSupport.isPlainText(source.fileName());
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        String text = new String(source.bytes(), StandardCharsets.UTF_8);
        ParsedBlock block = new ParsedBlock(
                0,
                BlockType.TEXT,
                text,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        return new ParsedDocument(
                source.documentId(),
                source.fileName(),
                DocumentExtensionSupport.sourceTypeFromFileName(source.fileName()),
                PARSER_NAME,
                PARSER_VERSION,
                Map.of(),
                List.of(block));
    }
}
