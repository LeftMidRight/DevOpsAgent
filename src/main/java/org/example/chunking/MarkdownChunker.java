package org.example.chunking;

import org.example.config.DocumentChunkConfig;
import org.example.context.TokenEstimator;
import org.example.service.ChunkDraft;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 TokenEstimator 估算切块，不是按豆包模型的真实 tokenizer。
 */
@Service
public class MarkdownChunker {

    private static final Pattern SENTENCE = Pattern.compile("[^。！？.!?；;]+[。！？.!?；;]?");

    private final TokenEstimator tokenEstimator;
    private final DocumentChunkConfig config;

    public MarkdownChunker(TokenEstimator tokenEstimator, DocumentChunkConfig config) {
        this.tokenEstimator = tokenEstimator;
        this.config = config;
    }

    public List<ChunkDraft> chunk(MarkdownDocument document, String fileName) {
        List<ChunkDraft> chunks = new ArrayList<>();
        ProseBuffer prose = new ProseBuffer();
        for (MarkdownElement element : document.elements()) {
            if (element.type() == MarkdownElementType.HEADING) {
                flushProse(chunks, prose, fileName);
                continue;
            }
            if (element.type() == MarkdownElementType.PARAGRAPH || element.type() == MarkdownElementType.QUOTE) {
                if (!prose.accepts(element)) {
                    flushProse(chunks, prose, fileName);
                }
                appendProse(chunks, prose, element, fileName);
                continue;
            }
            flushProse(chunks, prose, fileName);
            if (element.type() == MarkdownElementType.TABLE) {
                chunks.addAll(splitTable(element, fileName));
            } else if (element.type() == MarkdownElementType.CODE) {
                chunks.addAll(splitCode(element, fileName));
            } else if (element.type() == MarkdownElementType.LIST) {
                chunks.addAll(splitList(element, fileName));
            }
        }
        flushProse(chunks, prose, fileName);
        applyOverlap(chunks, fileName);
        return reindex(chunks, fileName);
    }

    public static String retrievalText(String fileName, List<String> headingPath, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Document] ").append(fileName == null ? "" : fileName).append('\n');
        if (headingPath != null && !headingPath.isEmpty()) {
            sb.append("[Section] ").append(String.join(" > ", headingPath)).append("\n\n");
        } else {
            sb.append('\n');
        }
        sb.append(content == null ? "" : content);
        return sb.toString();
    }

    private void appendProse(List<ChunkDraft> chunks, ProseBuffer prose, MarkdownElement element, String fileName) {
        if (tokenEstimator.estimate(element.content()) > config.getMaxTokens()) {
            flushProse(chunks, prose, fileName);
            for (String piece : pack(sentences(element.content()), config.getMaxTokens())) {
                chunks.add(draft(piece, element.headingPath(), contentType(element), fileName));
            }
            return;
        }
        int next = tokenEstimator.estimate(prose.text() + "\n\n" + element.content());
        if (!prose.isEmpty() && next > config.getTargetTokens()) {
            flushProse(chunks, prose, fileName);
        }
        prose.add(element);
    }

    private void flushProse(List<ChunkDraft> chunks, ProseBuffer prose, String fileName) {
        if (prose.isEmpty()) {
            return;
        }
        chunks.add(draft(prose.text(), prose.headingPath, prose.contentType, fileName));
        prose.clear();
    }

    private List<ChunkDraft> splitTable(MarkdownElement element, String fileName) {
        String[] lines = element.content().split("\n", -1);
        if (element.content().toLowerCase().contains("<table")
                || lines.length < 2
                || tokenEstimator.estimate(element.content()) <= config.getMaxTokens()) {
            return limited(element.content(), element.headingPath(), "table", fileName);
        }
        String header = lines[0] + "\n" + lines[1];
        List<String> rows = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                rows.add(lines[i]);
            }
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String row : rows) {
            if (tokenEstimator.estimate(renderTable(header, List.of(), row)) > config.getMaxTokens()) {
                if (!group.isEmpty()) {
                    chunks.addAll(limited(renderTable(header, group, null), element.headingPath(), "table", fileName));
                    group.clear();
                }
                chunks.addAll(splitOversizedTableRow(header, row, element.headingPath(), fileName));
                continue;
            }
            String candidate = renderTable(header, group, row);
            if (!group.isEmpty() && tokenEstimator.estimate(candidate) > config.getMaxTokens()) {
                chunks.addAll(limited(renderTable(header, group, null), element.headingPath(), "table", fileName));
                group.clear();
            }
            group.add(row);
        }
        if (!group.isEmpty()) {
            chunks.addAll(limited(renderTable(header, group, null), element.headingPath(), "table", fileName));
        }
        return chunks;
    }

    private List<ChunkDraft> splitOversizedTableRow(
            String header, String row, List<String> headingPath, String fileName) {
        String[] headerLines = header.split("\n", 2);
        String headerLine = headerLines[0];
        String separator = headerLines.length > 1 ? headerLines[1] : "| --- |";
        return splitCells(headerLine, separator, new ArrayList<>(java.util.Arrays.asList(tableCells(row))), headingPath, fileName);
    }

    private List<ChunkDraft> splitCells(
            String headerLine,
            String separator,
            List<String> cells,
            List<String> headingPath,
            String fileName) {
        String rendered = headerLine + "\n" + separator + "\n" + renderCells(cells);
        if (tokenEstimator.estimate(rendered) <= config.getMaxTokens()) {
            return List.of(draft(rendered, headingPath, "table", fileName));
        }
        int widest = 0;
        for (int i = 1; i < cells.size(); i++) {
            if (tokenEstimator.estimate(cells.get(i)) > tokenEstimator.estimate(cells.get(widest))) {
                widest = i;
            }
        }
        String cell = cells.get(widest);
        if (cell.length() <= 1) {
            return limited(rendered, headingPath, "table", fileName);
        }
        int middle = Math.max(1, cell.length() / 2);
        List<String> left = new ArrayList<>(cells);
        List<String> right = new ArrayList<>(cells);
        left.set(widest, cell.substring(0, middle).trim());
        right.set(widest, cell.substring(middle).trim());
        List<ChunkDraft> chunks = new ArrayList<>();
        chunks.addAll(splitCells(headerLine, separator, left, headingPath, fileName));
        chunks.addAll(splitCells(headerLine, separator, right, headingPath, fileName));
        return chunks;
    }

    private static String[] tableCells(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    private static String renderCells(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private static String renderTable(String header, List<String> rows, String extra) {
        StringBuilder sb = new StringBuilder(header);
        for (String row : rows) {
            sb.append('\n').append(row);
        }
        if (extra != null) {
            sb.append('\n').append(extra);
        }
        return sb.toString();
    }

    private List<ChunkDraft> splitCode(MarkdownElement element, String fileName) {
        if (tokenEstimator.estimate(element.content()) <= config.getMaxTokens()) {
            return limited(element.content(), element.headingPath(), "code", fileName);
        }
        String language = element.metadata().getOrDefault("language", "");
        String[] lines = element.content().split("\n", -1);
        int start = 1;
        int end = lines.length;
        if (end > 0 && isFenceLine(lines[end - 1])) {
            end--;
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (int i = start; i < end; i++) {
            String line = lines[i];
            if (tokenEstimator.estimate(renderCode(language, List.of(line))) > config.getMaxTokens()) {
                if (!group.isEmpty()) {
                    chunks.addAll(limited(renderCode(language, group), element.headingPath(), "code", fileName));
                    group.clear();
                }
                chunks.addAll(splitWrapped(language, line, element.headingPath(), fileName));
                continue;
            }
            group.add(line);
            if (tokenEstimator.estimate(renderCode(language, group)) > config.getMaxTokens() && group.size() > 1) {
                String last = group.remove(group.size() - 1);
                chunks.addAll(limited(renderCode(language, group), element.headingPath(), "code", fileName));
                group.clear();
                group.add(last);
            }
        }
        if (!group.isEmpty()) {
            chunks.addAll(limited(renderCode(language, group), element.headingPath(), "code", fileName));
        }
        return chunks;
    }

    private List<ChunkDraft> splitWrapped(String language, String line, List<String> headingPath, String fileName) {
        int overhead = tokenEstimator.estimate(renderCode(language, List.of("")));
        int budget = Math.max(1, config.getMaxTokens() - overhead);
        List<ChunkDraft> chunks = new ArrayList<>();
        for (String part : splitOversized(line, budget)) {
            chunks.addAll(limited(renderCode(language, List.of(part)), headingPath, "code", fileName));
        }
        return chunks;
    }

    private static boolean isFenceLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static String renderCode(String language, List<String> lines) {
        StringBuilder sb = new StringBuilder("```");
        if (language != null && !language.isBlank()) {
            sb.append(language);
        }
        for (String line : lines) {
            sb.append('\n').append(line);
        }
        sb.append("\n```");
        return sb.toString();
    }

    private List<ChunkDraft> splitList(MarkdownElement element, String fileName) {
        if (tokenEstimator.estimate(element.content()) <= config.getMaxTokens()) {
            return limited(element.content(), element.headingPath(), "list", fileName);
        }
        String[] lines = element.content().split("\n", -1);
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (tokenEstimator.estimate(line) > config.getMaxTokens()) {
                if (!group.isEmpty()) {
                    chunks.addAll(limited(String.join("\n", group), element.headingPath(), "list", fileName));
                    group.clear();
                }
                chunks.addAll(splitListItem(line, element.headingPath(), fileName));
                continue;
            }
            group.add(line);
            if (tokenEstimator.estimate(String.join("\n", group)) > config.getMaxTokens() && group.size() > 1) {
                String last = group.remove(group.size() - 1);
                chunks.addAll(limited(String.join("\n", group), element.headingPath(), "list", fileName));
                group.clear();
                group.add(last);
            }
        }
        if (!group.isEmpty()) {
            chunks.addAll(limited(String.join("\n", group), element.headingPath(), "list", fileName));
        }
        return chunks;
    }

    private List<ChunkDraft> splitListItem(String line, List<String> headingPath, String fileName) {
        int markerEnd = 0;
        while (markerEnd < line.length() && (line.charAt(markerEnd) == ' ' || line.charAt(markerEnd) == '\t')) {
            markerEnd++;
        }
        int contentStart = markerEnd;
        while (contentStart < line.length() && line.charAt(contentStart) != ' ') {
            contentStart++;
        }
        if (contentStart < line.length()) {
            contentStart++;
        }
        String marker = line.substring(0, contentStart);
        String body = line.substring(contentStart);
        List<ChunkDraft> chunks = new ArrayList<>();
        for (String piece : pack(sentences(body), Math.max(1, config.getMaxTokens() - tokenEstimator.estimate(marker)))) {
            chunks.addAll(limited(marker + piece, headingPath, "list", fileName));
        }
        return chunks;
    }

    private List<ChunkDraft> limited(String content, List<String> headingPath, String contentType, String fileName) {
        if (tokenEstimator.estimate(content) <= config.getMaxTokens()) {
            return List.of(draft(content, headingPath, contentType, fileName));
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        for (String part : splitOversized(content, config.getMaxTokens())) {
            if (!part.isBlank()) {
                chunks.add(draft(part, headingPath, contentType, fileName));
            }
        }
        return chunks;
    }

    private void applyOverlap(List<ChunkDraft> chunks, String fileName) {
        for (int i = 1; i < chunks.size(); i++) {
            ChunkDraft previous = chunks.get(i - 1);
            ChunkDraft current = chunks.get(i);
            if (!"paragraph".equals(previous.contentType()) || !"paragraph".equals(current.contentType())) {
                continue;
            }
            if (!previous.headingPath().equals(current.headingPath())) {
                continue;
            }
            String overlap = tailSentences(previous.content(), config.getOverlapTokens());
            if (overlap.isBlank()) {
                continue;
            }
            String merged = overlap + "\n" + current.content();
            if (tokenEstimator.estimate(merged) > config.getMaxTokens()) {
                continue;
            }
            chunks.set(i, draft(merged, current.headingPath(), current.contentType(), fileName));
        }
    }

    private String tailSentences(String text, int overlapTokens) {
        if (overlapTokens <= 0) {
            return "";
        }
        List<String> sentences = sentences(text);
        List<String> tail = new ArrayList<>();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            tail.add(0, sentences.get(i));
            if (tokenEstimator.estimate(String.join("", tail)) >= overlapTokens) {
                break;
            }
        }
        return String.join("", tail).trim();
    }

    private List<String> pack(List<String> pieces, int maxTokens) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            for (String part : splitOversized(piece, maxTokens)) {
                if (current.length() > 0 && tokenEstimator.estimate(current + part) > maxTokens) {
                    packed.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            packed.add(current.toString().trim());
        }
        return packed;
    }

    private List<String> splitOversized(String text, int maxTokens) {
        if (tokenEstimator.estimate(text) <= maxTokens) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        String remaining = text;
        while (tokenEstimator.estimate(remaining) > maxTokens) {
            int end = 1;
            while (end < remaining.length() && tokenEstimator.estimate(remaining.substring(0, end + 1)) <= maxTokens) {
                end++;
            }
            parts.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
        }
        if (!remaining.isBlank()) {
            parts.add(remaining);
        }
        return parts;
    }

    private static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            if (!matcher.group().isBlank()) {
                sentences.add(matcher.group());
            }
        }
        if (sentences.isEmpty() && text != null && !text.isBlank()) {
            sentences.add(text);
        }
        return sentences;
    }

    private ChunkDraft draft(String content, List<String> headingPath, String contentType, String fileName) {
        String title = headingPath == null || headingPath.isEmpty() ? null : headingPath.get(headingPath.size() - 1);
        return new ChunkDraft(
                content.trim(),
                title,
                0,
                contentType,
                headingPath == null ? List.of() : headingPath,
                retrievalText(fileName, headingPath, content.trim()));
    }

    private static List<ChunkDraft> reindex(List<ChunkDraft> chunks, String fileName) {
        List<ChunkDraft> indexed = new ArrayList<>();
        int index = 0;
        for (ChunkDraft chunk : chunks) {
            if (chunk.content().isBlank()) {
                continue;
            }
            indexed.add(new ChunkDraft(
                    chunk.content(),
                    chunk.title(),
                    index++,
                    chunk.contentType(),
                    chunk.headingPath(),
                    retrievalText(fileName, chunk.headingPath(), chunk.content())));
        }
        return indexed;
    }

    private static String contentType(MarkdownElement element) {
        return element.type() == MarkdownElementType.QUOTE ? "quote" : "paragraph";
    }

    private static final class ProseBuffer {
        private final StringBuilder body = new StringBuilder();
        private List<String> headingPath = List.of();
        private String contentType = "paragraph";

        boolean isEmpty() {
            return body.isEmpty();
        }

        boolean accepts(MarkdownElement element) {
            return isEmpty() || headingPath.equals(element.headingPath());
        }

        void add(MarkdownElement element) {
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(element.content());
            headingPath = element.headingPath();
            if (element.type() == MarkdownElementType.QUOTE && body.length() == element.content().length()) {
                contentType = "quote";
            } else if (element.type() == MarkdownElementType.PARAGRAPH) {
                contentType = "paragraph";
            }
        }

        String text() {
            return body.toString();
        }

        void clear() {
            body.setLength(0);
            headingPath = List.of();
            contentType = "paragraph";
        }
    }
}
