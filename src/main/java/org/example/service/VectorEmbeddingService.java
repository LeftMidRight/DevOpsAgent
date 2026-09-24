package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.VolcengineModelConfig;
import org.example.config.VolcengineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量嵌入服务：调用火山方舟多模态向量化 API（OpenAI 兼容）。
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    private final VolcengineProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VectorEmbeddingService(VolcengineProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(VolcengineModelConfig.trimTrailingSlash(properties.getEmbedding().getBaseUrl()))
                .build();
    }

    @PostConstruct
    public void init() {
        VolcengineModelConfig.validateApiKey(properties.getApiKey());
        logger.info("火山方舟 Embedding 服务初始化完成 - model: {}, baseUrl: {}, path: {}",
                properties.getEmbedding().getModel(),
                properties.getEmbedding().getBaseUrl(),
                properties.getEmbedding().getPath());
    }

    public List<Float> generateEmbedding(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        return requestEmbeddings(List.of(content)).get(0);
    }

    public List<List<Float>> generateEmbeddings(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }
        return requestEmbeddings(contents);
    }

    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private List<List<Float>> requestEmbeddings(List<String> contents) {
        List<List<Float>> embeddings = new ArrayList<>(contents.size());
        for (String content : contents) {
            embeddings.add(requestSingleEmbedding(content));
        }
        return embeddings;
    }

    private List<Float> requestSingleEmbedding(String content) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getEmbedding().getModel());
            body.put("encoding_format", "float");
            body.put("dimensions", properties.getEmbedding().getDimensions());
            body.put("input", List.of(Map.of("type", "text", "text", content)));

            String responseBody = restClient.post()
                    .uri(properties.getEmbedding().getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseEmbeddingResponse(responseBody);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content.length(), e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    private List<Float> parseEmbeddingResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new RuntimeException("Embedding API 错误: " + error.path("message").asText(error.toString()));
        }

        JsonNode data = root.path("data");
        JsonNode embeddingNode = data.isArray()
                ? data.path(0).path("embedding")
                : data.path("embedding");

        if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
            throw new RuntimeException("Embedding API 返回空向量: " + responseBody);
        }

        List<Float> embedding = new ArrayList<>(embeddingNode.size());
        for (JsonNode value : embeddingNode) {
            embedding.add((float) value.asDouble());
        }
        logger.debug("成功生成向量嵌入, 维度: {}", embedding.size());
        return embedding;
    }
}
