package org.example.parsing.mineru;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.config.MinerUProperties;
import org.example.parsing.DocumentParseException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * mineru.net 免费 Agent 轻量接口：申请签名上传地址，PUT 文件字节，再轮询 Markdown 结果。
 */
public class MinerUAgentClient {

    private static final Set<String> PENDING_STATES = Set.of("waiting-file", "uploading", "pending", "running");

    private final MinerUProperties properties;
    private final RestClient restClient;

    public MinerUAgentClient(MinerUProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public String parseByFile(byte[] fileBytes, String fileName) {
        JsonObject created = submitFile(fileName);
        String taskId = requireString(created, "task_id", "submit");
        putBytes(requireString(created, "file_url", "submit"), fileBytes);
        return download(pollMarkdownUrl(taskId));
    }

    private JsonObject submitFile(String fileName) {
        JsonObject body = new JsonObject();
        body.addProperty("file_name", fileName);
        body.addProperty("language", properties.getLanguage());
        body.addProperty("enable_table", properties.isEnableTable());
        body.addProperty("is_ocr", properties.isOcr());
        body.addProperty("enable_formula", properties.isEnableFormula());
        return post("/api/v1/agent/parse/file", body);
    }

    private void putBytes(String uploadUrl, byte[] fileBytes) {
        try {
            restClient.put()
                    .uri(URI.create(uploadUrl))
                    .contentLength(fileBytes.length)
                    .body(outputStream -> outputStream.write(fileBytes))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new DocumentParseException("上传文件到 MinerU 失败: HTTP " + e.getStatusCode().value(), e);
        }
    }

    private String pollMarkdownUrl(String taskId) {
        Instant deadline = Instant.now().plus(properties.getMaxPollDuration());
        while (Instant.now().isBefore(deadline)) {
            JsonObject data = get("/api/v1/agent/parse/" + taskId);
            String state = requireString(data, "state", "poll");
            if ("done".equals(state)) {
                return requireString(data, "markdown_url", "poll");
            }
            if ("failed".equals(state)) {
                String message = textOrNull(data, "err_msg");
                throw new DocumentParseException("MinerU agent 解析失败: " + (message == null ? state : message));
            }
            if (!PENDING_STATES.contains(state)) {
                throw new DocumentParseException("MinerU agent 未知状态: " + state);
            }
            sleep(properties.getPollInterval());
        }
        throw new DocumentParseException("MinerU agent 解析超时: " + properties.getMaxPollDuration());
    }

    private String download(String markdownUrl) {
        try {
            String markdown = restClient.get()
                    .uri(URI.create(markdownUrl))
                    .retrieve()
                    .body(String.class);
            if (markdown == null || markdown.isBlank()) {
                throw new DocumentParseException("MinerU agent 返回的 Markdown 为空");
            }
            return markdown;
        } catch (RestClientResponseException e) {
            throw new DocumentParseException("下载 MinerU Markdown 失败: HTTP " + e.getStatusCode().value(), e);
        }
    }

    private JsonObject post(String path, JsonObject body) {
        try {
            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            return unwrap(response, path);
        } catch (RestClientResponseException e) {
            throw new DocumentParseException("MinerU agent 请求失败: HTTP " + e.getStatusCode().value(), e);
        }
    }

    private JsonObject get(String path) {
        try {
            String response = restClient.get()
                    .uri(path)
                    .retrieve()
                    .body(String.class);
            return unwrap(response, path);
        } catch (RestClientResponseException e) {
            throw new DocumentParseException("MinerU agent 查询失败: HTTP " + e.getStatusCode().value(), e);
        }
    }

    private static JsonObject unwrap(String response, String stage) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code != 0) {
            String msg = textOrNull(root, "msg");
            throw new DocumentParseException("MinerU agent " + stage + " 失败: " + (msg == null ? code : msg));
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            throw new DocumentParseException("MinerU agent " + stage + " 缺少 data");
        }
        return root.getAsJsonObject("data");
    }

    private static String requireString(JsonObject json, String field, String stage) {
        String value = textOrNull(json, field);
        if (value == null || value.isBlank()) {
            throw new DocumentParseException("MinerU agent " + stage + " 缺少 " + field);
        }
        return value;
    }

    private static String textOrNull(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        return json.get(field).getAsString();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("MinerU agent 轮询被中断", e);
        }
    }
}
