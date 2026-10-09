package org.example.parsing;

import java.util.Locale;
import java.util.Set;

public final class DocumentExtensionSupport {

    private static final Set<String> PLAIN_TEXT = Set.of("txt", "md", "markdown");
    private static final Set<String> MINERU = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xlsx",
            "png", "jpg", "jpeg", "webp");

    private DocumentExtensionSupport() {}

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isPlainText(String fileName) {
        return PLAIN_TEXT.contains(extensionOf(fileName));
    }

    public static boolean isMinerU(String fileName) {
        return MINERU.contains(extensionOf(fileName));
    }

    public static String sourceTypeFromFileName(String fileName) {
        String ext = extensionOf(fileName);
        if (ext.isEmpty()) {
            return "unknown";
        }
        if ("md".equals(ext) || "markdown".equals(ext)) {
            return "markdown";
        }
        return ext;
    }

    public static String mimeTypeOf(String fileName) {
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "txt" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
