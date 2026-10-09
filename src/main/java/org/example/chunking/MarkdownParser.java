package org.example.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownParser {

    public MarkdownDocument parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new MarkdownDocument(List.of());
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<MarkdownElement> elements = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        int order = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (isFence(line)) {
                String marker = fenceMarker(line);
                int end = i + 1;
                while (end < lines.length && !closesFence(lines[end], marker)) {
                    end++;
                }
                if (end < lines.length) {
                    end++;
                }
                String language = fenceLanguage(line);
                elements.add(element(MarkdownElementType.CODE, join(lines, i, end), headings, order++,
                        language.isEmpty() ? Map.of() : Map.of("language", language)));
                i = end;
                continue;
            }
            if (isTableStart(lines, i)) {
                int end = i + 2;
                while (end < lines.length && looksLikeTableRow(lines[end])) {
                    end++;
                }
                elements.add(element(MarkdownElementType.TABLE, join(lines, i, end), headings, order++, Map.of()));
                i = end;
                continue;
            }
            if (isHeading(line)) {
                int level = headingLevel(line);
                String title = line.replaceFirst("^#{1,6}\\s+", "").trim();
                while (headings.size() >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(title);
                elements.add(element(MarkdownElementType.HEADING, title, headings, order++, Map.of("level", Integer.toString(level))));
                i++;
                continue;
            }
            if (isQuote(line)) {
                int end = i + 1;
                while (end < lines.length && isQuote(lines[end])) {
                    end++;
                }
                elements.add(element(MarkdownElementType.QUOTE, join(lines, i, end), headings, order++, Map.of()));
                i = end;
                continue;
            }
            if (isHtmlTableStart(line)) {
                int end = i;
                StringBuilder table = new StringBuilder();
                while (end < lines.length) {
                    if (table.length() > 0) {
                        table.append('\n');
                    }
                    table.append(lines[end]);
                    end++;
                    if (table.toString().toLowerCase().contains("</table>")) {
                        break;
                    }
                }
                elements.add(element(MarkdownElementType.TABLE, table.toString(), headings, order++, Map.of()));
                i = end;
                continue;
            }
            if (isListItem(line)) {
                int end = i + 1;
                while (end < lines.length && (isListItem(lines[end]) || isListContinuation(lines[end]))) {
                    end++;
                }
                elements.add(element(MarkdownElementType.LIST, join(lines, i, end), headings, order++, Map.of()));
                i = end;
                continue;
            }
            int end = i + 1;
            while (end < lines.length && !lines[end].isBlank() && !startsBlock(lines, end)) {
                end++;
            }
            elements.add(element(MarkdownElementType.PARAGRAPH, join(lines, i, end), headings, order++, Map.of()));
            i = end;
        }
        return new MarkdownDocument(elements);
    }

    private static boolean startsBlock(String[] lines, int index) {
        String line = lines[index];
        return isFence(line) || isHeading(line) || isQuote(line) || isListItem(line)
                || isTableStart(lines, index) || isHtmlTableStart(line);
    }

    private static MarkdownElement element(
            MarkdownElementType type,
            String content,
            List<String> headings,
            int order,
            Map<String, String> metadata) {
        return new MarkdownElement(type, content.trim(), List.copyOf(headings), order, metadata);
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static boolean isFence(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static String fenceMarker(String line) {
        return line.trim().startsWith("~~~") ? "~~~" : "```";
    }

    private static boolean closesFence(String line, String marker) {
        return line.trim().startsWith(marker);
    }

    private static String fenceLanguage(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("```")) {
            return trimmed.substring(3).trim();
        }
        if (trimmed.startsWith("~~~")) {
            return trimmed.substring(3).trim();
        }
        return "";
    }

    private static boolean isHeading(String line) {
        return line.matches("^#{1,6}\\s+\\S.*");
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    private static boolean isQuote(String line) {
        return line.matches("^\\s*>.*");
    }

    private static boolean isListItem(String line) {
        return line.matches("^\\s*([-*+]|\\d+[.)])\\s+\\S.*");
    }

    private static boolean isListContinuation(String line) {
        return !line.isBlank() && line.matches("^\\s{2,}\\S.*");
    }

    private static boolean isHtmlTableStart(String line) {
        return line.toLowerCase().contains("<table");
    }

    private static boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length && looksLikeTableRow(lines[index]) && isSeparator(lines[index + 1]);
    }

    private static boolean looksLikeTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.contains("|") && !trimmed.startsWith("#");
    }

    private static boolean isSeparator(String line) {
        String trimmed = line.trim();
        return trimmed.contains("---") && trimmed.contains("|");
    }
}
