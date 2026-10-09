package org.example.conversation;

import org.example.context.TokenEstimator;
import org.example.conversation.ConversationHistoryPersistence.DurableConversationRecord;
import org.example.conversation.ConversationHistoryPersistence.TurnCommitCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ConversationTurnCommitJdbcTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void schema() throws Exception {
        if (!dockerAvailable()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("""
                    CREATE TABLE conversations (
                      id varchar(128) PRIMARY KEY,
                      summary text,
                      summary_until_seq bigint NOT NULL DEFAULT 0,
                      next_seq bigint NOT NULL DEFAULT 1,
                      created_at timestamptz NOT NULL DEFAULT now(),
                      updated_at timestamptz NOT NULL DEFAULT now(),
                      expires_at timestamptz,
                      working_state jsonb,
                      revision bigint NOT NULL DEFAULT 0
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE conversation_messages (
                      id uuid PRIMARY KEY,
                      conversation_id varchar(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                      request_id uuid NOT NULL,
                      seq bigint NOT NULL,
                      role varchar(32) NOT NULL,
                      content text NOT NULL,
                      status varchar(32) NOT NULL DEFAULT 'COMMITTED',
                      tool_name varchar(128),
                      tool_call_id varchar(128),
                      metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
                      token_count int NOT NULL DEFAULT 0,
                      created_at timestamptz NOT NULL DEFAULT now(),
                      UNIQUE (conversation_id, seq)
                    )
                    """);
        }
    }

    @Test
    @EnabledIf("dockerAvailable")
    void commitsUserAssistantAndWorkingStateAtomicallyAndReloadsOnlyCompleteTurns() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            ConversationHistoryPersistence history = context.getBean(ConversationHistoryPersistence.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            WorkingState snapshot = new WorkingState();
            snapshot.setGoal("定位 CPU");
            snapshot.knownFacts().add("CPU 92%");

            long revision = history.commitTurn(new TurnCommitCommand(
                    "session-db", UUID.randomUUID(), "为什么 CPU 高？", "先看 GC", snapshot, 0));

            assertEquals(1, revision);
            assertEquals(2, countMessages(jdbc, "session-db"));
            assertTrue(workingStateText(jdbc, "session-db").contains("CPU 92%"));
            assertEquals(1L, revision(jdbc, "session-db"));

            insert(jdbc, "session-db", 50, "user", "孤儿问题", "COMMITTED");
            insert(jdbc, "session-db", 51, "user", "失败问题", "FAILED");
            insert(jdbc, "session-db", 52, "assistant", "失败回答", "FAILED");

            DurableConversationRecord loaded = history.load("session-db").orElseThrow();
            RuntimeConversationState restored = RuntimeConversationState.restore("session-db", loaded);
            assertEquals(List.of("为什么 CPU 高？", "先看 GC"),
                    restored.messages().stream().map(message -> message.getText()).toList());
            assertEquals("定位 CPU", restored.workingState().goal());
            assertEquals(List.of("CPU 92%"), restored.workingState().knownFacts());
            assertFalse(restored.messages().stream().anyMatch(message -> "孤儿问题".equals(message.getText())));

            WorkingState next = snapshot.copy();
            next.setProgress("已检查 GC");
            assertThrows(IllegalStateException.class, () -> history.commitTurn(new TurnCommitCommand(
                    "session-db", UUID.randomUUID(), "重复写入", "不应该留下", next, 0)));
            assertEquals(5, countMessages(jdbc, "session-db"));
            assertEquals(0, countContent(jdbc, "重复写入"));
            assertEquals(0, countContent(jdbc, "不应该留下"));
            assertEquals(1L, revision(jdbc, "session-db"));
            assertFalse(workingStateText(jdbc, "session-db").contains("已检查 GC"));
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int countContent(JdbcTemplate jdbc, String content) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE content = ?",
                Integer.class,
                content);
        return count == null ? 0 : count;
    }

    private static int countMessages(JdbcTemplate jdbc, String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ?",
                Integer.class,
                conversationId);
        return count == null ? 0 : count;
    }

    private static String workingStateText(JdbcTemplate jdbc, String conversationId) {
        return jdbc.queryForObject(
                "SELECT working_state::text FROM conversations WHERE id = ?",
                String.class,
                conversationId);
    }

    private static long revision(JdbcTemplate jdbc, String conversationId) {
        Long revision = jdbc.queryForObject(
                "SELECT revision FROM conversations WHERE id = ?",
                Long.class,
                conversationId);
        return revision == null ? -1 : revision;
    }

    private static void insert(
            JdbcTemplate jdbc,
            String conversationId,
            long sequence,
            String role,
            String content,
            String status) {
        jdbc.update(
                "INSERT INTO conversation_messages "
                        + "(id, conversation_id, request_id, seq, role, content, status, token_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                UUID.randomUUID(), conversationId, UUID.randomUUID(), sequence, role, content, status);
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            dataSource.setDriverClassName("org.postgresql.Driver");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ConversationHistoryPersistence history(JdbcTemplate jdbcTemplate) {
            return new ConversationRepository(jdbcTemplate, new TokenEstimator());
        }
    }
}
