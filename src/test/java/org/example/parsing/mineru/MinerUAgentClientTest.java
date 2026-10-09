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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MinerUAgentClientTest {

    private MockRestServiceServer server;
    private MinerUAgentClient client;

    @BeforeEach
    void setUp() {
        MinerUProperties properties = new MinerUProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://mineru.test");
        properties.setPollInterval(Duration.ofMillis(10));
        properties.setMaxPollDuration(Duration.ofSeconds(2));

        RestClient.Builder builder = RestClient.builder().baseUrl("http://mineru.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MinerUAgentClient(properties, builder.build());
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void parseByFile_uploadsBytesAndReturnsMarkdown() {
        server.expect(requestTo("http://mineru.test/api/v1/agent/parse/file"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"task-1\",\"file_url\":\"http://mineru.test/oss/upload\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/oss/upload"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo("http://mineru.test/api/v1/agent/parse/task-1"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"task-1\",\"state\":\"running\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/api/v1/agent/parse/task-1"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"task-1\",\"state\":\"done\",\"markdown_url\":\"http://mineru.test/cdn/full.md\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/cdn/full.md"))
                .andRespond(withSuccess("# Title\n\nbody", MediaType.TEXT_PLAIN));

        assertEquals("# Title\n\nbody", client.parseByFile("pdf".getBytes(StandardCharsets.UTF_8), "a.pdf"));
    }

    @Test
    void parseByFile_failsWhenTaskFailed() {
        server.expect(requestTo("http://mineru.test/api/v1/agent/parse/file"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"task-2\",\"file_url\":\"http://mineru.test/oss/upload\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mineru.test/oss/upload"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo("http://mineru.test/api/v1/agent/parse/task-2"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"ok\",\"data\":{\"state\":\"failed\",\"err_msg\":\"too many pages\"}}",
                        MediaType.APPLICATION_JSON));

        DocumentParseException error = assertThrows(
                DocumentParseException.class,
                () -> client.parseByFile(new byte[] {1}, "a.pdf"));
        assertEquals("MinerU agent 解析失败: too many pages", error.getMessage());
    }
}
