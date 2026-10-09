package org.example.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class GinTokenIndexTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    @EnabledIf("dockerAvailable")
    void ginOverlapReturnsMatchingChunksAndUsesIndex() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var statement = connection.createStatement();
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("""
                    CREATE TABLE document_chunks (
                      id uuid PRIMARY KEY,
                      tokens text[] NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_chunks_tokens ON document_chunks USING GIN (tokens)");
            insert(connection, "11111111-1111-1111-1111-111111111111", "oomkilled", "memory");
            insert(connection, "22222222-2222-2222-2222-222222222222", "cpu", "usage");
            insert(connection, "33333333-3333-3333-3333-333333333333", "memory", "leak");

            assertEquals(List.of("11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333"),
                    ids(connection, "memory"));
            assertEquals(List.of("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"),
                    ids(connection, "cpu", "oomkilled"));
            assertTrue(ids(connection, "not-exist").isEmpty());

            statement.execute("SET enable_seqscan = off");
            try (ResultSet plan = statement.executeQuery(
                    "EXPLAIN SELECT id, tokens FROM document_chunks WHERE tokens && ARRAY['oomkilled']::text[]")) {
                StringBuilder text = new StringBuilder();
                while (plan.next()) {
                    text.append(plan.getString(1)).append('\n');
                }
                assertTrue(text.toString().contains("idx_chunks_tokens"), text.toString());
            }
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void insert(Connection connection, String id, String... tokens) throws Exception {
        try (var ps = connection.prepareStatement("INSERT INTO document_chunks (id, tokens) VALUES (?::uuid, ?)")) {
            ps.setString(1, id);
            ps.setArray(2, connection.createArrayOf("text", tokens));
            ps.executeUpdate();
        }
    }

    private static List<String> ids(Connection connection, String... tokens) throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT id::text FROM document_chunks WHERE tokens && ? ORDER BY id")) {
            ps.setArray(1, connection.createArrayOf("text", tokens));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }
}
