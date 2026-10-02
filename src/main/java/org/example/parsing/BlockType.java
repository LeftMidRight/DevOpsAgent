package org.example.parsing;

/**
 * Stable block types for ingestion chunking (not a 1:1 copy of MinerU types).
 */
public enum BlockType {
    TEXT,
    HEADING,
    TABLE,
    LIST,
    CODE,
    IMAGE_CAPTION,
    SLIDE,
    SHEET,
    UNKNOWN
}
