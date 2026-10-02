package org.example.parsing.mineru;

import org.example.config.MinerUProperties;
import org.example.parsing.DocumentParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MinerUClientTest {

    private MockRestServiceServer server;
    private MinerUClient client;

    @BeforeEach
    void setUp() {
        MinerUProperties properties = new MinerUProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://mineru.test");
        properties.setApiKey("test-key");
        properties.setTier("standard");
        properties.setOutputFormat("structured_content");
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setTransferTimeout(Duration.ofSeconds(5));
        properties.setPollInterval(Duration.ofMillis(10));
        properties.setMaxPollDuration(Duration.ofSeconds(2));

        RestClient.Builder builder = RestClient.builder().baseUrl("http://mineru.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MinerUClient(properties, builder.build());
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void parseToStructuredContentJson_completesHappyPath() {
        server.expect(requestTo("http://mineru.test/v1/health"))
                .andRespond(withSuccess(
                        "{\"features\":{\"output_formats\":[\"structured_content\"]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "up-1",
                          "status": "pending",
                          "upload_url": "/v1/uploads/up-1/content",
                          "upload_method": "PUT",
                          "upload_headers": {}
                        }
                        """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/uploads/up-1/content"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://mineru.test/v1/uploads/up-1/complete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"status\":\"completed\",\"file\":{\"id\":\"file-1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/parse/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"job_id\":\"job-1\",\"status\":\"queued\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/parse/jobs/job-1"))
                .andRespond(withSuccess(
                        """
                        {
                          "status": "completed",
                          "files": [
                            {
                              "status": "completed",
                              "output_files": {
                                "structured_content": {"file_id": "out-1"}
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/files/out-1/content"))
                .andRespond(withSuccess(
                        "{\"pages\":[{\"page_idx\":0,\"blocks\":[{\"type\":\"text\",\"content\":\"ok\"}]}]}",
                        MediaType.APPLICATION_JSON));

        String json = client.parseToStructuredContentJson(
                "hello".getBytes(StandardCharsets.UTF_8), "doc.txt", "text/plain");
        assertTrue(json.contains("ok"));
    }

    @Test
    void parseToStructuredContentJson_failsOnPartialJob() {
        server.expect(requestTo("http://mineru.test/v1/health"))
                .andRespond(withSuccess(
                        "{\"features\":{\"output_formats\":[\"structured_content\"]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/uploads"))
                .andRespond(withSuccess(
                        """
                        {"id":"up-2","status":"completed","file":{"id":"file-2"}}
                        """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/parse/jobs"))
                .andRespond(withSuccess("{\"job_id\":\"job-2\",\"status\":\"queued\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/v1/parse/jobs/job-2"))
                .andRespond(withSuccess("{\"status\":\"partial\",\"files\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(
                DocumentParseException.class,
                () -> client.parseToStructuredContentJson(new byte[] {1}, "doc.pdf", "application/pdf"));
    }
}
