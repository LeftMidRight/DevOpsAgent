package org.example.parsing;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public ParsedDocument parse(DocumentSource source) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(source)) {
                return parser.parse(source);
            }
        }
        throw new DocumentParseException(
                "不支持的文件格式: " + DocumentExtensionSupport.extensionOf(source.fileName()));
    }
}
