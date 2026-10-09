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
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
        private long timeoutMs = 180_000L;
    }

    @Getter
    @Setter
    public static class Embedding {
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
        /** 向量接口路径，相对 baseUrl */
        private String path = "/embeddings/multimodal";
        private String model = "doubao-embedding-vision";
        private int dimensions = 1024;
        /**
         * 请求体 input 形态：multimodal= [{"type":"text","text":...}]（火山多模态向量）；
         * text= 纯字符串（OpenAI 兼容文本向量，如阿里 text-embedding 系列）。
         */
        private String inputStyle = "multimodal";
    }
}
