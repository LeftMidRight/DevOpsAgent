package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 聊天 / 运维请求（兼容大小写别名）。
 */
@Getter
@Setter
public class ChatRequest {

    @JsonProperty("Id")
    @JsonAlias({"id", "ID"})
    private String id;

    @JsonProperty("Question")
    @JsonAlias({"question", "QUESTION"})
    private String question;

    @JsonProperty("Mode")
    @JsonAlias({"mode", "MODE"})
    private String mode;
}
