package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 火山方舟（豆包）模型配置。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "volcengine")
public class VolcengineProperties {

    private String apiKey;

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();

    @Getter
    @Setter
    public static class Chat {
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";
        /** 相对 baseUrl，火山方舟 coding 端点为 /chat/completions（无 /v1 前缀） */
        private String completionsPath = "/chat/completions";
        private long timeoutMs = 180_000L;
    }

    @Getter
    @Setter
    public static class Embedding {
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";
        /** 多模态向量接口路径，相对 baseUrl */
        private String path = "/embeddings/multimodal";
        private String model = "doubao-embedding-vision";
        private int dimensions = 1024;
    }
}
