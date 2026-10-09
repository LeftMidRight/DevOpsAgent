package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Token-estimate-aware 分片配置。
 * 用 TokenEstimator 估算长度，不是豆包 embedding tokenizer 的真实 token。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "document.chunk")
public class DocumentChunkConfig {

    private int targetTokens = 450;

    private int maxTokens = 600;

    private int overlapTokens = 60;

    public void setTargetTokens(int targetTokens) {
        this.targetTokens = targetTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setOverlapTokens(int overlapTokens) {
        this.overlapTokens = overlapTokens;
    }
}
