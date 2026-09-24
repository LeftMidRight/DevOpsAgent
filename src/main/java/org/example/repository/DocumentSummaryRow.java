package org.example.repository;

import java.time.Instant;

public record DocumentSummaryRow(String fileName, int chunkCount, Instant indexedAt) {
}
