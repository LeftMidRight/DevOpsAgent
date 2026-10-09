package org.example.conversation;

import org.example.context.TokenEstimator;
import org.example.conversation.ConversationHistoryPersistence.DurableConversationRecord;
import org.example.conversation.ConversationHistoryPersistence.TurnCommitCommand;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class ConversationRepository implements ConversationHistoryPersistence {

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

    @Override
    public Optional<DurableConversationRecord> load(String conversationId) {
        List<ConversationHead> heads = jdbcTemplate.query(
                "SELECT revision, working_state::text AS working_state FROM conversations WHERE id = ?",
                (rs, rowNum) -> new ConversationHead(rs.getLong("revision"), rs.getString("working_state")),
                conversationId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        ConversationHead head = heads.get(0);
        List<ConversationMessage> messages = jdbcTemplate.query(
                "SELECT id, conversation_id, request_id, seq, role, content, status, token_count, created_at "
                        + "FROM conversation_messages "
                        + "WHERE conversation_id = ? AND status = 'COMMITTED' ORDER BY seq",
                this::mapMessage,
                conversationId);
        return Optional.of(new DurableConversationRecord(
                head.revision(), WorkingState.fromJson(head.workingState()), messages));
    }

    @Override
    @Transactional
    public long commitTurn(TurnCommitCommand commit) {
        createIfAbsent(commit.conversationId());
        appendMessage(commit.conversationId(), commit.requestId(), "user", commit.userContent(), "COMMITTED");
        appendMessage(
                commit.conversationId(), commit.requestId(), "assistant", commit.assistantContent(), "COMMITTED");
        try {
            Long revision = jdbcTemplate.queryForObject(
                    "UPDATE conversations SET working_state = CAST(? AS jsonb), revision = revision + 1, "
                            + "updated_at = now() WHERE id = ? AND revision = ? RETURNING revision",
                    Long.class,
                    commit.workingState().toJson(),
                    commit.conversationId(),
                    commit.expectedRevision());
            if (revision == null) {
                throw new IllegalStateException("会话提交失败: " + commit.conversationId());
            }
            return revision;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("会话版本冲突，本轮未提交: " + commit.conversationId(), e);
        }
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
                "UPDATE conversations SET summary = NULL, summary_until_seq = 0, next_seq = 1, "
                        + "working_state = NULL, revision = 0, updated_at = now() WHERE id = ?",
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

    private record ConversationHead(long revision, String workingState) {
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
