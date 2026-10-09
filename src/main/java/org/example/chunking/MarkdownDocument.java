package org.example.chunking;

import java.util.List;

public record MarkdownDocument(List<MarkdownElement> elements) {
    public MarkdownDocument {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }
}
