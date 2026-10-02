package org.example.parsing;

import java.util.Collections;
import java.util.Map;

public record ParsedBlock(
        int index,
        BlockType type,
        String content,
        String title,
        Integer pageNumber,
        Integer slideNumber,
        String sheetName,
        Integer headingLevel,
        Map<String, Object> metadata
) {
    public ParsedBlock {
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }
}
