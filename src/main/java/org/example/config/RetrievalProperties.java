package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalProperties {

    private int vectorTopN = 10;
    private int bm25TopN = 10;
    private int rrfK = 60;
    private int embeddingDim = 1024;

    public void setVectorTopN(int vectorTopN) {
        this.vectorTopN = vectorTopN;
    }

    public void setBm25TopN(int bm25TopN) {
        this.bm25TopN = bm25TopN;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public void setEmbeddingDim(int embeddingDim) {
        this.embeddingDim = embeddingDim;
    }
}
