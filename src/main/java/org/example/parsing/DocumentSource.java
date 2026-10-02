package org.example.parsing;

import java.util.UUID;

public record DocumentSource(
        UUID documentId,
        String fileName,
        String mediaType,
        String objectKey,
        byte[] bytes
) {}
