package org.example.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档注册表访问：记录文档的对象存储位置、分块状态与分片数。
 */
@Repository
public class DocumentRepository {

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String SELECT_COLUMNS =
            "SELECT id, file_name, object_key, size_bytes, status, chunk_count, error_message, created_at, indexed_at "
                    + "FROM knowledge_documents";

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, String fileName, String objectKey, long sizeBytes) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_documents (id, file_name, object_key, size_bytes, status) VALUES (?, ?, ?, ?, ?)",
                id, fileName, objectKey, sizeBytes, STATUS_UPLOADED);
    }

    public Optional<KnowledgeDocumentRow> findById(UUID id) {
        List<KnowledgeDocumentRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = ?", this::mapRow, id);
        return rows.stream().findFirst();
    }

    public List<KnowledgeDocumentRow> findAll() {
        return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY created_at DESC", this::mapRow);
    }

    public void markIndexed(UUID id, int chunkCount) {
        jdbcTemplate.update(
                "UPDATE knowledge_documents SET status = ?, chunk_count = ?, error_message = NULL, "
                        + "indexed_at = now(), updated_at = now() WHERE id = ?",
                STATUS_INDEXED, chunkCount, id);
    }

    public void markFailed(UUID id, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE knowledge_documents SET status = ?, error_message = ?, updated_at = now() WHERE id = ?",
                STATUS_FAILED, errorMessage, id);
    }

    public void deleteById(UUID id) {
        jdbcTemplate.update("DELETE FROM knowledge_documents WHERE id = ?", id);
    }

    private KnowledgeDocumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp indexedAt = rs.getTimestamp("indexed_at");
        return new KnowledgeDocumentRow(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                rs.getString("object_key"),
                rs.getLong("size_bytes"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                indexedAt == null ? null : indexedAt.toInstant());
    }
}