package org.example.parsing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ParsedDocument(
        UUID documentId,
        String fileName,
        String sourceType,
        String parser,
        String parserVersion,
        Map<String, Object> metadata,
        List<ParsedBlock> blocks
) {}
