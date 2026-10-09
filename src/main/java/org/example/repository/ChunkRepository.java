package org.example.repository;

import org.example.retrieval.ChunkRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.example.retrieval.Bm25CorpusStats;
import org.example.retrieval.Bm25Index;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    static String toVectorLiteral(List<Float> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    @Transactional
    public void replaceDocumentChunks(UUID documentId, String fileName, List<ChunkRecord> rows) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
        for (ChunkRecord row : rows) {
            jdbcTemplate.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO document_chunks (id, document_id, file_name, title, content, retrieval_text, tokens, embedding, chunk_index, metadata) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?::jsonb)");
                ps.setObject(1, row.id());
                ps.setObject(2, documentId);
                ps.setString(3, fileName);
                ps.setString(4, row.title());
                ps.setString(5, row.content());
                ps.setString(6, row.retrievalText());
                Array tokenArray = connection.createArrayOf("text", row.tokens().toArray(new String[0]));
                ps.setArray(7, tokenArray);
                ps.setString(8, toVectorLiteral(row.embedding()));
                ps.setInt(9, row.chunkIndex());
                ps.setString(10, row.metadataJson());
                return ps;
            });
        }
    }

    public List<Bm25Index.Bm25Document> findBm25Candidates(List<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return List.of();
        }
        String[] tokens = queryTokens.stream().distinct().toArray(String[]::new);
        return jdbcTemplate.query(connection -> {
            var ps = connection.prepareStatement(
                    "SELECT id, tokens FROM document_chunks WHERE tokens && ?");
            ps.setArray(1, connection.createArrayOf("text", tokens));
            return ps;
        }, (rs, rowNum) -> new Bm25Index.Bm25Document(
                rs.getObject("id", UUID.class).toString(),
                readTokens(rs)));
    }

    public Bm25CorpusStats loadCorpusStats(List<String> queryTokens) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS document_count, COALESCE(AVG(cardinality(tokens)), 0) AS avg_document_length "
                        + "FROM document_chunks");
        long documentCount = ((Number) row.get("document_count")).longValue();
        double averageLength = ((Number) row.get("avg_document_length")).doubleValue();
        Map<String, Long> frequencies = new HashMap<>();
        if (queryTokens != null) {
            for (String term : queryTokens.stream().distinct().toList()) {
                Long count = jdbcTemplate.query(connection -> {
                    var ps = connection.prepareStatement(
                            "SELECT COUNT(*) FROM document_chunks WHERE tokens @> ?");
                    ps.setArray(1, connection.createArrayOf("text", new String[] {term}));
                    return ps;
                }, rs -> rs.next() ? rs.getLong(1) : 0L);
                frequencies.put(term, count == null ? 0L : count);
            }
        }
        return new Bm25CorpusStats(documentCount, averageLength, frequencies);
    }

    public List<String> searchIdsByEmbedding(List<Float> query, int topN) {
        String vectorLiteral = toVectorLiteral(query);
        return jdbcTemplate.query(
                "SELECT id FROM document_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class).toString(),
                vectorLiteral,
                topN);
    }

    public List<ChunkRecord> findByDocumentId(UUID documentId) {
        return jdbcTemplate.query(
                "SELECT id, file_name, title, content, retrieval_text, tokens, chunk_index, metadata "
                        + "FROM document_chunks WHERE document_id = ? ORDER BY chunk_index",
                this::mapRow,
                documentId);
    }

    public List<ChunkRecord> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        UUID[] uuidIds = ids.stream().map(UUID::fromString).toArray(UUID[]::new);
        return jdbcTemplate.query(connection -> {
            var ps = connection.prepareStatement(
                    "SELECT id, file_name, title, content, retrieval_text, tokens, chunk_index, metadata "
                            + "FROM document_chunks WHERE id = ANY(?)");
            ps.setArray(1, connection.createArrayOf("uuid", uuidIds));
            return ps;
        }, this::mapRow);
    }

    @Transactional
    public int deleteByDocumentId(UUID documentId) {
        return jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
    }

    private ChunkRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String fileName = rs.getString("file_name");
        String title = rs.getString("title");
        String content = rs.getString("content");
        String retrievalText = rs.getString("retrieval_text");
        List<String> tokens = readTokens(rs);
        int chunkIndex = rs.getInt("chunk_index");
        String metadataJson = rs.getString("metadata");
        return new ChunkRecord(id, fileName, title, content, retrievalText, tokens, null, chunkIndex, metadataJson);
    }

    private static List<String> readTokens(ResultSet rs) throws SQLException {
        Array tokensArray = rs.getArray("tokens");
        if (tokensArray == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((String[]) tokensArray.getArray()));
    }
}
