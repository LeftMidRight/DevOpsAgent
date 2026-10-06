package org.example;

import org.example.service.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.mcp.client.enabled=false", "prometheus.mock-enabled=true",
        "prometheus.base-url=http://127.0.0.1:9090",
        "agent.model=doubao-test",
        "management.endpoints.web.exposure.include=health,prometheus"
})
class ApplicationSmokeTest {

    @MockitoBean
    ObjectStorageService objectStorage;

    @MockitoBean
    io.minio.MinioClient minioClient;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    javax.sql.DataSource dataSource;

    @Value("${local.server.port}")
    int port;

    @Test
    void servesStaticUiJsonAndPrometheusWithBoot4() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        assertEquals(200, get(client, "/").statusCode());
        HttpResponse<String> documents = get(client, "/api/knowledge-base/documents");
        assertEquals(200, documents.statusCode());
        assertTrue(documents.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var body = new tools.jackson.databind.json.JsonMapper().readTree(documents.body());
        assertEquals(200, body.path("code").asInt(), documents.body());
        assertTrue(body.path("data").isArray(), documents.body());
        assertEquals(0, body.path("data").size());
        HttpResponse<String> metrics = get(client, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_memory"), metrics.body());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
