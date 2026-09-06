package org.example.service;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import org.example.dto.DocumentChunk;
import org.example.repository.ChunkRepository;
import org.example.retrieval.ChunkRecord;
import org.example.retrieval.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 PostgreSQL
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    private final ChineseTokenizer tokenizer = new ChineseTokenizer();
    private final Gson gson = new Gson();

    /**
     * 索引指定目录下的所有文件
     *
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty())
                    ? directoryPath : uploadPath;

            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();

            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            File[] files = directory.listFiles((dir, name) ->
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}",
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     *
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();

        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "";
        List<ChunkRecord> rows = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);

            List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
            List<String> tokens = tokenizer.tokenize(chunk.getContent());
            Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());
            String metadataJson = gson.toJson(metadata);
            UUID id = UUID.nameUUIDFromBytes(
                    (fileName + "_" + chunk.getChunkIndex()).getBytes(StandardCharsets.UTF_8));

            rows.add(new ChunkRecord(
                    id,
                    fileName,
                    chunk.getTitle(),
                    chunk.getContent(),
                    tokens,
                    vector,
                    chunk.getChunkIndex(),
                    metadataJson));

            logger.info("✓ 分片 {}/{} 向量生成成功", i + 1, chunks.size());
        }

        chunkRepository.replaceFileChunks(fileName, rows);
        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();

        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");

        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";

        metadata.put("_source", normalizedPath);
        metadata.put("_file_name", fileNameStr);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }

        return metadata;
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
