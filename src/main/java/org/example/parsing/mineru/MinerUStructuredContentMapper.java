package org.example.parsing.mineru;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.parsing.BlockType;
import org.example.parsing.DocumentExtensionSupport;
import org.example.parsing.DocumentParseException;
import org.example.parsing.ParsedBlock;
import org.example.parsing.ParsedDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinerUStructuredContentMapper {

    public static final String PARSER_NAME = "mineru";
    public static final String PARSER_VERSION = "4.x";

    private MinerUStructuredContentMapper() {}

    public static ParsedDocument map(UUID documentId, String fileName, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<ParsedBlock> blocks = new ArrayList<>();
        int index = 0;
        if (root.has("pages") && root.get("pages").isJsonArray()) {
            for (JsonElement pageElement : root.getAsJsonArray("pages")) {
                if (!pageElement.isJsonObject()) {
                    continue;
                }
                JsonObject page = pageElement.getAsJsonObject();
                Integer pageNumber = pageNumber(page);
                if (!page.has("blocks") || !page.get("blocks").isJsonArray()) {
                    continue;
                }
                for (JsonElement blockElement : page.getAsJsonArray("blocks")) {
                    if (!blockElement.isJsonObject()) {
                        continue;
                    }
                    blocks.add(mapBlock(blockElement.getAsJsonObject(), index++, pageNumber));
                }
            }
        }
        if (blocks.isEmpty()) {
            throw new DocumentParseException("MinerU structured_content 未包含可索引文本块");
        }
        String parserVersion = producerVersion(root);
        return new ParsedDocument(
                documentId,
                fileName,
                DocumentExtensionSupport.sourceTypeFromFileName(fileName),
                PARSER_NAME,
                parserVersion,
                Map.of(),
                blocks);
    }

    private static ParsedBlock mapBlock(JsonObject block, int index, Integer pageNumber) {
        String mineruType = textOrNull(block, "type");
        BlockType type = toBlockType(mineruType);
        String content = extractContent(block);
        if (content == null || content.isBlank()) {
            content = "";
        }
        String title = textOrNull(block, "title");
        Integer slideNumber = intOrNull(block, "slide_number", "slideNumber", "slide_idx");
        String sheetName = textOrNull(block, "sheet_name", "sheetName");
        Integer headingLevel = intOrNull(block, "heading_level", "headingLevel", "level");
        Map<String, Object> metadata = new HashMap<>();
        if (mineruType != null) {
            metadata.put("mineruType", mineruType);
        }
        return new ParsedBlock(index, type, content.trim(), title, pageNumber, slideNumber, sheetName, headingLevel, metadata);
    }

    private static BlockType toBlockType(String mineruType) {
        if (mineruType == null) {
            return BlockType.UNKNOWN;
        }
        String lower = mineruType.toLowerCase();
        if (lower.contains("title") || lower.contains("heading")) {
            return BlockType.HEADING;
        }
        if (lower.contains("table")) {
            return BlockType.TABLE;
        }
        if (lower.contains("list")) {
            return BlockType.LIST;
        }
        if (lower.contains("code")) {
            return BlockType.CODE;
        }
        if (lower.contains("slide")) {
            return BlockType.SLIDE;
        }
        if (lower.contains("sheet")) {
            return BlockType.SHEET;
        }
        if (lower.contains("caption") || lower.contains("image")) {
            return BlockType.IMAGE_CAPTION;
        }
        return BlockType.TEXT;
    }

    private static String extractContent(JsonObject block) {
        if (block.has("content")) {
            JsonElement content = block.get("content");
            if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
                return content.getAsString();
            }
            if (content.isJsonArray()) {
                return joinContentArray(content.getAsJsonArray());
            }
        }
        if (block.has("text")) {
            return block.get("text").getAsString();
        }
        return null;
    }

    private static String joinContentArray(JsonArray array) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                appendLine(sb, element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("content")) {
                    JsonElement nested = obj.get("content");
                    if (nested.isJsonPrimitive() && nested.getAsJsonPrimitive().isString()) {
                        appendLine(sb, nested.getAsString());
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line.trim());
    }

    private static Integer pageNumber(JsonObject page) {
        Integer value = intOrNull(page, "page_idx", "pageNumber", "page_number");
        if (value == null) {
            return null;
        }
        return value + 1;
    }

    private static String textOrNull(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                continue;
            }
            JsonElement element = obj.get(key);
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
        }
        return null;
    }

    private static String producerVersion(JsonObject root) {
        if (!root.has("metadata") || !root.get("metadata").isJsonObject()) {
            return PARSER_VERSION;
        }
        JsonObject metadata = root.getAsJsonObject("metadata");
        if (!metadata.has("producer") || !metadata.get("producer").isJsonObject()) {
            return PARSER_VERSION;
        }
        JsonObject producer = metadata.getAsJsonObject("producer");
        if (producer.has("version") && producer.get("version").isJsonPrimitive()) {
            return producer.get("version").getAsString();
        }
        return PARSER_VERSION;
    }
}
