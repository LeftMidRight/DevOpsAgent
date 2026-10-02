package org.example.parsing.mineru;

import org.example.config.MinerUProperties;
import org.example.parsing.DocumentExtensionSupport;
import org.example.parsing.DocumentParseException;
import org.example.parsing.DocumentParser;
import org.example.parsing.DocumentSource;
import org.example.parsing.ParsedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnBean(MinerUClient.class)
public class MinerUDocumentParser implements DocumentParser {

    private final MinerUClient minerUClient;
    private final MinerUProperties properties;

    public MinerUDocumentParser(MinerUClient minerUClient, MinerUProperties properties) {
        this.minerUClient = minerUClient;
        this.properties = properties;
    }

    @Override
    public boolean supports(DocumentSource source) {
        return properties.isEnabled() && DocumentExtensionSupport.isMinerU(source.fileName());
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        if (!properties.isEnabled()) {
            throw new DocumentParseException("MinerU 未启用，无法解析: " + source.fileName());
        }
        String mimeType = source.mediaType() != null && !source.mediaType().isBlank()
                ? source.mediaType()
                : DocumentExtensionSupport.mimeTypeOf(source.fileName());
        String json = minerUClient.parseToStructuredContentJson(source.bytes(), source.fileName(), mimeType);
        ParsedDocument mapped = MinerUStructuredContentMapper.map(source.documentId(), source.fileName(), json);
        return new ParsedDocument(
                mapped.documentId(),
                mapped.fileName(),
                mapped.sourceType(),
                mapped.parser(),
                mapped.parserVersion(),
                Map.of("structuredContentJson", json),
                mapped.blocks());
    }
}
