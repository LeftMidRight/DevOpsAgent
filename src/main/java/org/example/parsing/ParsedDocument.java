package org.example.parsing;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record ParsedDocument(
        UUID documentId,
        String fileName,
        String sourceType,
        String parser,
        String parserVersion,
        String content,
        Map<String, Object> metadata
) {
    public ParsedDocument {
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
        content = content == null ? "" : content;
    }
}
