package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClearRequest {

    @JsonProperty("Id")
    @JsonAlias({"id", "ID"})
    private String id;
}
