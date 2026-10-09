package org.example.parsing;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
        return new ParsedDocument(
                source.documentId(),
                source.fileName(),
                DocumentExtensionSupport.sourceTypeFromFileName(source.fileName()),
                PARSER_NAME,
                PARSER_VERSION,
                text,
                Map.of());
    }
}
