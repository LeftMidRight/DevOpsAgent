package org.example.conversation;

import org.example.context.TokenEstimator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TokenEstimator tokenEstimator;

    public ConversationRepository(JdbcTemplate jdbcTemplate, TokenEstimator tokenEstimator) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenEstimator = tokenEstimator;
    }

    public void createIfAbsent(String conversationId) {
        jdbcTemplate.update(
                "INSERT INTO conversations (id) VALUES (?) ON CONFLICT (id) DO NOTHING",
                conversationId);
    }

    public Optional<ConversationState> find(String conversationId) {
        List<ConversationState> rows = jdbcTemplate.query(
                "SELECT id, summary, summary_until_seq, created_at, updated_at "
                        + "FROM conversations WHERE id = ?",
                this::mapConversation,
                conversationId);
        return rows.stream().findFirst();
    }

    @Transactional
    public ConversationMessage appendMessage(
            String conversationId,
            UUID requestId,
            String role,
            String content,
            String status) {
        return appendMessage(conversationId, requestId, role, content, status, null);
    }

    @Transactional
    public ConversationMessage appendMessage(
            String conversationId,
            UUID requestId,
            String role,
            String content,
            String status,
            String metadataJson) {
        createIfAbsent(conversationId);
        Long sequence = jdbcTemplate.queryForObject(
                "UPDATE conversations SET next_seq = next_seq + 1, updated_at = now() "
                        + "WHERE id = ? RETURNING next_seq - 1",
                Long.class,
                conversationId);
        if (sequence == null) {
            throw new IllegalStateException("无法为会话分配消息序号: " + conversationId);
        }

        UUID id = UUID.randomUUID();
        int tokenCount = tokenEstimator.estimate(content);
        jdbcTemplate.update(
                "INSERT INTO conversation_messages "
                        + "(id, conversation_id, request_id, seq, role, content, status, metadata, token_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                id, conversationId, requestId, sequence, role, content, status,
                metadataJson == null ? "{}" : metadataJson, tokenCount);
        return new ConversationMessage(
                id, conversationId, requestId, sequence, role, content, status, tokenCount, Instant.now());
    }

    public List<ConversationMessage> findContextMessages(String conversationId, UUID currentRequestId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, request_id, seq, role, content, status, token_count, created_at "
                        + "FROM conversation_messages "
                        + "WHERE conversation_id = ? "
                        + "AND (status = 'COMMITTED' OR (request_id = ? AND status = 'PENDING')) "
                        + "ORDER BY seq",
                this::mapMessage,
                conversationId,
                currentRequestId);
    }

    public List<ConversationMessage> findCommittedAfter(String conversationId, long sequence) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, request_id, seq, role, content, status, token_count, created_at "
                        + "FROM conversation_messages "
                        + "WHERE conversation_id = ? AND status = 'COMMITTED' AND seq > ? ORDER BY seq",
                this::mapMessage,
                conversationId,
                sequence);
    }

    @Transactional
    public void completeTurn(ConversationTurn turn, String answer) {
        completeTurn(turn, answer, null);
    }

    @Transactional
    public void completeTurn(ConversationTurn turn, String answer, String metadataJson) {
        int updated = jdbcTemplate.update(
                "UPDATE conversation_messages SET status = 'COMMITTED' "
                        + "WHERE id = ? AND conversation_id = ? AND status = 'PENDING'",
                turn.userMessageId(), turn.conversationId());
        if (updated != 1) {
            throw new IllegalStateException("当前用户消息不存在或已完成: " + turn.userMessageId());
        }
        appendMessage(turn.conversationId(), turn.requestId(), "assistant", answer, "COMMITTED", metadataJson);
    }

    public void failTurn(ConversationTurn turn) {
        jdbcTemplate.update(
                "UPDATE conversation_messages SET status = 'FAILED' "
                        + "WHERE id = ? AND conversation_id = ? AND status = 'PENDING'",
                turn.userMessageId(), turn.conversationId());
    }

    public boolean updateSummary(
            String conversationId,
            long expectedSummaryUntil,
            long newSummaryUntil,
            String summary) {
        return jdbcTemplate.update(
                "UPDATE conversations SET summary = ?, summary_until_seq = ?, updated_at = now() "
                        + "WHERE id = ? AND summary_until_seq = ?",
                summary, newSummaryUntil, conversationId, expectedSummaryUntil) == 1;
    }

    @Transactional
    public void clear(String conversationId) {
        jdbcTemplate.update("DELETE FROM conversation_messages WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update(
                "UPDATE conversations SET summary = NULL, summary_until_seq = 0, next_seq = 1, updated_at = now() "
                        + "WHERE id = ?",
                conversationId);
    }

    public int countCompletedTurns(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages "
                        + "WHERE conversation_id = ? AND role = 'assistant' AND status = 'COMMITTED'",
                Integer.class,
                conversationId);
        return count == null ? 0 : count;
    }

    private ConversationState mapConversation(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationState(
                rs.getString("id"),
                rs.getString("summary"),
                rs.getLong("summary_until_seq"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ConversationMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationMessage(
                rs.getObject("id", UUID.class),
                rs.getString("conversation_id"),
                rs.getObject("request_id", UUID.class),
                rs.getLong("seq"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getInt("token_count"),
                rs.getTimestamp("created_at").toInstant());
    }
}
