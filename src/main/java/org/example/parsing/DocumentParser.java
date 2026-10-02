package org.example.parsing;

public interface DocumentParser {

    boolean supports(DocumentSource source);

    ParsedDocument parse(DocumentSource source);
}
