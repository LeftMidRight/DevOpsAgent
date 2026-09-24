package org.example.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "context")
public class ContextProperties {

    private int maxContextTokens = 24000;
    private int reservedOutputTokens = 4000;
    private int systemAndToolsReserveTokens = 6000;
    private int summaryTokens = 2000;
    private int recentMessageTokens = 8000;
    private int compressionTriggerTokens = 8000;
}
