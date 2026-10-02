package org.example.service;

import java.util.UUID;

public final class ParsedArtifactPaths {

    private ParsedArtifactPaths() {}

    public static String structuredContentKey(UUID documentId) {
        return "parsed/" + documentId + "/structured_content.json";
    }
}
