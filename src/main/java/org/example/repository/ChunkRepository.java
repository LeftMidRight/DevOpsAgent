package org.example.repository;

import org.example.retrieval.ChunkRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
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
    public void replaceFileChunks(String fileName, List<ChunkRecord> rows) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE file_name = ?", fileName);
        for (ChunkRecord row : rows) {
            jdbcTemplate.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO document_chunks (id, file_name, title, content, tokens, embedding, chunk_index, metadata) "
                                + "VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?::jsonb)");
                ps.setObject(1, row.id());
                ps.setString(2, row.fileName());
                ps.setString(3, row.title());
                ps.setString(4, row.content());
                Array tokenArray = connection.createArrayOf("text", row.tokens().toArray(new String[0]));
                ps.setArray(5, tokenArray);
                ps.setString(6, toVectorLiteral(row.embedding()));
                ps.setInt(7, row.chunkIndex());
                ps.setString(8, row.metadataJson());
                return ps;
            });
        }
    }

    public List<ChunkRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, file_name, title, content, tokens, chunk_index, metadata FROM document_chunks",
                this::mapRow);
    }

    public List<ChunkRecord> searchByEmbedding(List<Float> query, int topN) {
        String vectorLiteral = toVectorLiteral(query);
        return jdbcTemplate.query(
                "SELECT id, file_name, title, content, tokens, chunk_index, metadata "
                        + "FROM document_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                this::mapRow,
                vectorLiteral,
                topN);
    }

    private ChunkRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String fileName = rs.getString("file_name");
        String title = rs.getString("title");
        String content = rs.getString("content");
        Array tokensArray = rs.getArray("tokens");
        List<String> tokens = tokensArray == null
                ? List.of()
                : Arrays.asList((String[]) tokensArray.getArray());
        int chunkIndex = rs.getInt("chunk_index");
        String metadataJson = rs.getString("metadata");
        return new ChunkRecord(id, fileName, title, content, tokens, null, chunkIndex, metadataJson);
    }
}
