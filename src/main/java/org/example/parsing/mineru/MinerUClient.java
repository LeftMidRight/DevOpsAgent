package org.example.parsing.mineru;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.config.MinerUProperties;
import org.example.parsing.DocumentParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * MinerU V1 HTTP client: upload lifecycle, parse job, artifact download.
 */
public class MinerUClient {

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "partial", "failed", "canceled");
    private static final Set<String> JOB_STATUSES = Set.of("queued", "running", "completed", "partial", "failed", "canceled");

    private final MinerUProperties properties;
    private final RestClient restClient;
    private volatile boolean healthChecked;

    public MinerUClient(MinerUProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public String parseToStructuredContentJson(byte[] fileBytes, String fileName, String mimeType) {
        ensureHealth();
        JsonObject uploadSession = createUpload(fileName, fileBytes.length, mimeType, sha256(fileBytes));
        String uploadStatus = requireString(uploadSession, "status", "create upload");
        JsonObject fileHolder = uploadSession;
        if ("pending".equals(uploadStatus)) {
            String uploadId = requireString(uploadSession, "id", "create upload");
            String uploadUrl = requireString(uploadSession, "upload_url", "create upload");
            String uploadMethod = uploadSession.has("upload_method")
                    ? uploadSession.get("upload_method").getAsString()
                    : "PUT";
            uploadBytes(uploadSession, uploadUrl, uploadMethod, fileBytes);
            fileHolder = completeUpload(uploadId);
        } else if (!"completed".equals(uploadStatus)) {
            throw new DocumentParseException("MinerU create upload: unsupported status " + uploadStatus);
        }
        String fileId = requireString(fileHolder.getAsJsonObject("file"), "id", "upload file");
        String jobId = submitParseJob(fileId);
        JsonObject job = pollJob(jobId);
        String status = requireString(job, "status", "parse job");
        if ("partial".equals(status) || "failed".equals(status) || "canceled".equals(status)) {
            throw new DocumentParseException("MinerU parse job ended with status: " + status);
        }
        if (!"completed".equals(status)) {
            throw new DocumentParseException("MinerU parse job unexpected status: " + status);
        }
        String outputFileId = findStructuredContentFileId(job);
        return downloadFileContent(outputFileId);
    }

    private void ensureHealth() {
        if (healthChecked) {
            return;
        }
        String body = restClient.get()
                .uri("/v1/health")
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonObject health = JsonParser.parseString(body).getAsJsonObject();
        if (!health.has("features") || !health.get("features").isJsonObject()) {
            throw new DocumentParseException("MinerU health: missing features");
        }
        JsonObject features = health.getAsJsonObject("features");
        if (!features.has("output_formats") || !features.get("output_formats").isJsonArray()) {
            throw new DocumentParseException("MinerU health: missing output_formats");
        }
        boolean supported = false;
        for (JsonElement element : features.getAsJsonArray("output_formats")) {
            if (properties.getOutputFormat().equals(element.getAsString())) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            throw new DocumentParseException(
                    "MinerU deployment does not support output format: " + properties.getOutputFormat());
        }
        healthChecked = true;
    }

    private JsonObject createUpload(String fileName, long bytes, String mimeType, String sha256sum) {
        JsonObject body = new JsonObject();
        body.addProperty("filename", fileName);
        body.addProperty("bytes", bytes);
        body.addProperty("mime_type", mimeType);
        body.addProperty("purpose", "parse");
        body.addProperty("sha256sum", sha256sum);
        String response = restClient.post()
                .uri("/v1/uploads")
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private void uploadBytes(JsonObject uploadSession, String uploadUrl, String method, byte[] fileBytes) {
        if (!"PUT".equalsIgnoreCase(method)) {
            throw new DocumentParseException("Unsupported MinerU upload_method: " + method);
        }
        URI target = resolveUploadUrl(uploadUrl);
        if (isSameOrigin(target)) {
            RestClient.RequestBodySpec spec = restClient.put().uri(toRelativeUri(target));
            applyUploadHeaders(uploadSession, spec, target);
            spec.body(fileBytes).retrieve().toBodilessEntity();
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getTransferTimeout());
        RestClient uploadClient = RestClient.builder().requestFactory(factory).build();
        RestClient.RequestBodySpec spec = uploadClient.put().uri(target);
        applyUploadHeaders(uploadSession, spec, target);
        spec.body(fileBytes).retrieve().toBodilessEntity();
    }

    private String toRelativeUri(URI target) {
        String path = target.getRawPath();
        if (target.getRawQuery() != null) {
            path = path + "?" + target.getRawQuery();
        }
        return path;
    }

    private URI resolveUploadUrl(String uploadUrl) {
        URI base = URI.create(normalizeBaseUrl(properties.getBaseUrl()));
        return base.resolve(uploadUrl);
    }

    private boolean isSameOrigin(URI target) {
        URI base = URI.create(normalizeBaseUrl(properties.getBaseUrl()));
        int basePort = base.getPort() > 0 ? base.getPort() : ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80);
        int targetPort = target.getPort() > 0 ? target.getPort() : ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80);
        return base.getScheme().equalsIgnoreCase(target.getScheme())
                && base.getHost().equalsIgnoreCase(target.getHost())
                && basePort == targetPort;
    }

    private void applyUploadHeaders(JsonObject uploadSession, RestClient.RequestBodySpec spec, URI target) {
        if (uploadSession.has("upload_headers") && uploadSession.get("upload_headers").isJsonObject()) {
            JsonObject headers = uploadSession.getAsJsonObject("upload_headers");
            for (String key : headers.keySet()) {
                spec.header(key, headers.get(key).getAsString());
            }
        }
        if (isSameOrigin(target)) {
            applyAuth(spec);
        }
    }

    private JsonObject completeUpload(String uploadId) {
        String response = restClient.post()
                .uri("/v1/uploads/{id}/complete", uploadId)
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        String status = requireString(json, "status", "complete upload");
        if (!"completed".equals(status)) {
            throw new DocumentParseException("MinerU complete upload: unexpected status " + status);
        }
        return json;
    }

    private String submitParseJob(String fileId) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "file_id");
        source.addProperty("file_id", fileId);
        JsonObject fileEntry = new JsonObject();
        fileEntry.add("source", source);
        JsonArray files = new JsonArray();
        files.add(fileEntry);
        JsonObject body = new JsonObject();
        body.add("files", files);
        body.addProperty("tier", properties.getTier());
        JsonArray formats = new JsonArray();
        formats.add(properties.getOutputFormat());
        body.add("output_formats", formats);
        String response = restClient.post()
                .uri("/v1/parse/jobs")
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        String status = requireString(json, "status", "create job");
        if (!JOB_STATUSES.contains(status)) {
            throw new DocumentParseException("MinerU create job: unsupported status " + status);
        }
        return requireString(json, "job_id", "create job");
    }

    private JsonObject pollJob(String jobId) {
        Instant deadline = Instant.now().plus(properties.getMaxPollDuration());
        while (Instant.now().isBefore(deadline)) {
            String response = restClient.get()
                    .uri("/v1/parse/jobs/{jobId}", jobId)
                    .headers(this::applyAuth)
                    .retrieve()
                    .body(String.class);
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            String status = requireString(json, "status", "poll job");
            if (!JOB_STATUSES.contains(status)) {
                throw new DocumentParseException("MinerU poll job: unsupported status " + status);
            }
            if (TERMINAL_STATUSES.contains(status)) {
                return json;
            }
            sleep(properties.getPollInterval());
        }
        throw new DocumentParseException("MinerU parse job timed out after " + properties.getMaxPollDuration());
    }

    private String findStructuredContentFileId(JsonObject job) {
        if (!job.has("files") || !job.get("files").isJsonArray()) {
            throw new DocumentParseException("MinerU job missing files");
        }
        String format = properties.getOutputFormat();
        for (JsonElement fileElement : job.getAsJsonArray("files")) {
            if (!fileElement.isJsonObject()) {
                continue;
            }
            JsonObject file = fileElement.getAsJsonObject();
            String fileStatus = textOrNull(file, "status");
            if (!"completed".equals(fileStatus)) {
                continue;
            }
            if (!file.has("output_files") || !file.get("output_files").isJsonObject()) {
                continue;
            }
            JsonObject outputs = file.getAsJsonObject("output_files");
            if (!outputs.has(format) || !outputs.get(format).isJsonObject()) {
                continue;
            }
            JsonObject formatRef = outputs.getAsJsonObject(format);
            return requireString(formatRef, "file_id", "output file");
        }
        throw new DocumentParseException("MinerU job completed but structured_content artifact missing");
    }

    private String downloadFileContent(String fileId) {
        return restClient.get()
                .uri("/v1/files/{fileId}/content", fileId)
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
    }

    private void applyAuth(HttpHeaders headers) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey());
        }
    }

    private void applyAuth(RestClient.RequestHeadersSpec<?> spec) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
    }

    private static String requireString(JsonObject json, String field, String stage) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new DocumentParseException("MinerU " + stage + ": missing " + field);
        }
        String value = json.get(field).getAsString();
        if (value.isBlank()) {
            throw new DocumentParseException("MinerU " + stage + ": empty " + field);
        }
        return value;
    }

    private static String textOrNull(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        return json.get(field).getAsString();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DocumentParseException("mineru.base-url is not configured");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("MinerU polling interrupted", e);
        }
    }
}
