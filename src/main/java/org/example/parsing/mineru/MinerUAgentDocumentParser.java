package org.example.parsing.mineru;

import org.example.config.MinerUProperties;
import org.example.parsing.DocumentExtensionSupport;
import org.example.parsing.DocumentParser;
import org.example.parsing.DocumentSource;
import org.example.parsing.ParsedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnBean(MinerUAgentClient.class)
public class MinerUAgentDocumentParser implements DocumentParser {

    public static final String PARSER_NAME = "mineru-agent";
    public static final String PARSER_VERSION = "agent-file";

    private final MinerUAgentClient client;
    private final MinerUProperties properties;

    public MinerUAgentDocumentParser(MinerUAgentClient client, MinerUProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean supports(DocumentSource source) {
        return properties.isEnabled() && DocumentExtensionSupport.isMinerU(source.fileName());
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        String markdown = client.parseByFile(source.bytes(), source.fileName());
        return fromMarkdown(source.documentId(), source.fileName(), markdown);
    }

    public static ParsedDocument fromMarkdown(UUID documentId, String fileName, String markdown) {
        return new ParsedDocument(
                documentId,
                fileName,
                DocumentExtensionSupport.sourceTypeFromFileName(fileName),
                PARSER_NAME,
                PARSER_VERSION,
                markdown,
                Map.of());
    }
}
