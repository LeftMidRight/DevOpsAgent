package org.example.chunking;

import java.util.List;
import java.util.Map;

public record MarkdownElement(
        MarkdownElementType type,
        String content,
        List<String> headingPath,
        int order,
        Map<String, String> metadata
) {
    public MarkdownElement {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        content = content == null ? "" : content;
    }
}
