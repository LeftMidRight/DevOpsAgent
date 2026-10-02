package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.example.parsing.BlockType;
import org.example.parsing.ParsedBlock;
import org.example.parsing.ParsedDocument;
import org.example.parsing.PlainTextDocumentParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StructureAwareChunker {

    private final DocumentChunkService documentChunkService;
    private final DocumentChunkConfig chunkConfig;

    public StructureAwareChunker(DocumentChunkService documentChunkService, DocumentChunkConfig chunkConfig) {
        this.documentChunkService = documentChunkService;
        this.chunkConfig = chunkConfig;
    }

    public List<ChunkDraft> chunk(ParsedDocument document) {
        if (PlainTextDocumentParser.PARSER_NAME.equals(document.parser())) {
            return chunkPlainText(document);
        }
        List<Segment> segments = buildSegments(document.blocks());
        List<ChunkDraft> drafts = new ArrayList<>();
        int chunkIndex = 0;
        String sectionTitle = null;
        for (Segment segment : segments) {
            if (segment.sectionTitle != null) {
                sectionTitle = segment.sectionTitle;
            }
            List<String> pieces = splitSegment(segment.body.toString(), segment.type);
            for (String piece : pieces) {
                if (piece.isBlank()) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>(segment.metadata);
                String title = segment.title != null ? segment.title : sectionTitle;
                if (title != null) {
                    meta.put("title", title);
                    meta.putIfAbsent("sectionTitle", title);
                }
                drafts.add(new ChunkDraft(piece, title, chunkIndex++, meta));
            }
        }
        return drafts;
    }

    private List<ChunkDraft> chunkPlainText(ParsedDocument document) {
        String content = document.blocks().isEmpty() ? "" : document.blocks().get(0).content();
        List<DocumentChunk> chunks = documentChunkService.chunkDocument(content, document.fileName());
        List<ChunkDraft> drafts = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceType", document.sourceType());
            meta.put("parser", document.parser());
            meta.put("parserVersion", document.parserVersion());
            meta.put("contentType", "text");
            if (chunk.getTitle() != null) {
                meta.put("title", chunk.getTitle());
            }
            drafts.add(new ChunkDraft(
                    chunk.getContent(),
                    chunk.getTitle(),
                    chunk.getChunkIndex(),
                    meta));
        }
        return drafts;
    }

    private List<Segment> buildSegments(List<ParsedBlock> blocks) {
        List<Segment> segments = new ArrayList<>();
        Segment current = null;
        for (ParsedBlock block : blocks) {
            if (block.content() == null || block.content().isBlank()) {
                continue;
            }
            boolean newBoundary = current == null || mustSplitBoundary(current, block);
            if (newBoundary) {
                if (current != null) {
                    segments.add(current);
                }
                current = new Segment(block);
            } else {
                current.append(block);
            }
            if (block.type() == BlockType.HEADING) {
                current.sectionTitle = firstNonBlank(block.title(), block.content());
            }
        }
        if (current != null) {
            segments.add(current);
        }
        return segments;
    }

    private boolean mustSplitBoundary(Segment current, ParsedBlock block) {
        if (!Objects.equals(current.slideNumber, block.slideNumber())) {
            return true;
        }
        if (!Objects.equals(normalize(current.sheetName), normalize(block.sheetName()))) {
            return true;
        }
        if (block.type() == BlockType.SLIDE || current.type == BlockType.SLIDE) {
            return block.type() == BlockType.SLIDE && current.type != BlockType.SLIDE;
        }
        if (block.type() == BlockType.SHEET || current.type == BlockType.SHEET) {
            return block.type() == BlockType.SHEET && current.type != BlockType.SHEET;
        }
        if (block.type() == BlockType.TABLE) {
            return true;
        }
        if (current.type == BlockType.TABLE) {
            return true;
        }
        return false;
    }

    private List<String> splitSegment(String body, BlockType type) {
        int maxSize = chunkConfig.getMaxSize();
        if (body.length() <= maxSize) {
            return List.of(body);
        }
        if (type == BlockType.TABLE) {
            return List.of(body);
        }
        List<DocumentChunk> chunks = documentChunkService.chunkDocument(body, "segment");
        List<String> pieces = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            pieces.add(chunk.getContent());
        }
        return pieces;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static final class Segment {
        private final BlockType type;
        private final StringBuilder body = new StringBuilder();
        private String title;
        private String sectionTitle;
        private final Integer slideNumber;
        private final String sheetName;
        private final Map<String, Object> metadata = new HashMap<>();

        Segment(ParsedBlock first) {
            this.type = first.type();
            this.slideNumber = first.slideNumber();
            this.sheetName = first.sheetName();
            append(first);
        }

        void append(ParsedBlock block) {
            if (title == null) {
                title = block.title();
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(block.content());
            if (block.pageNumber() != null) {
                metadata.putIfAbsent("pageStart", block.pageNumber());
                metadata.put("pageEnd", block.pageNumber());
            }
            if (block.slideNumber() != null) {
                metadata.put("slideNumber", block.slideNumber());
            }
            if (block.sheetName() != null) {
                metadata.put("sheetName", block.sheetName());
            }
            metadata.putIfAbsent("blockStart", block.index());
            metadata.put("blockEnd", block.index());
        }
    }
}
