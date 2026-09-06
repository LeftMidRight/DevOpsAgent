package org.example.retrieval;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RetrievedChunk {
    private String id;
    private String fileName;
    private String title;
    private String content;
    private float score;
    private String metadata;
}
