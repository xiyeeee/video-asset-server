package com.xiye.videoasset.ai.characters.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.ai.characters.model.PythonCharacterTask;
import com.xiye.videoasset.ai.characters.model.PythonCharacterTask.Submit;
import com.xiye.videoasset.ai.characters.persistence.PythonExtractionKind;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
class PythonCharacterClient {
    private static final Logger LOG = LoggerFactory.getLogger(PythonCharacterClient.class);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String ASSET_ENDPOINT = "/internal/v1/asset-extraction/tasks";
    private static final String ENDPOINT = "/internal/v1/character-extraction/tasks";
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final RestClient client;
    private final ObjectMapper mapper;

    PythonCharacterClient(RestClient.Builder builder, ObjectMapper mapper, CharacterExtractionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.pythonUrl().toString()).requestFactory(factory).build();
        this.mapper = mapper;
    }

    Reply poll(long id, int attempt, PythonExtractionKind kind) {
        return exchange(client.get().uri(endpoint(kind) + "/{taskId}", id), id, attempt, "查询", kind);
    }

    Reply submit(long id, int attempt, String source, double interval, PythonExtractionKind kind) {
        Object payload = kind == PythonExtractionKind.AUDIO_EXTRACTION
                ? new AudioSubmit(Long.toString(id), source, List.of("AUDIO"))
                : new Submit(Long.toString(id), source, interval);
        RestClient.RequestHeadersSpec<?> request = client.post().uri(endpoint(kind))
                .contentType(MediaType.APPLICATION_JSON).body(payload);
        return exchange(request, id, attempt, "提交", kind);
    }

    private static String endpoint(PythonExtractionKind kind) {
        return kind == PythonExtractionKind.AUDIO_EXTRACTION ? ASSET_ENDPOINT : ENDPOINT;
    }

    private Reply exchange(
            RestClient.RequestHeadersSpec<?> request, long id, int attempt, String operation, PythonExtractionKind kind
    ) {
        long start = System.nanoTime();
        Reply reply;
        try {
            reply = request.exchange((outbound, inbound) -> {
                int httpStatus = inbound.getStatusCode().value();
                String requestId = inbound.getHeaders().getFirst("X-Request-ID");
                requestId = requestId != null && REQUEST_ID.matcher(requestId).matches() ? requestId : null;
                if (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500) {
                    return new Reply(httpStatus, null, "PYTHON_UNAVAILABLE", requestId);
                }
                byte[] body = inbound.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    return new Reply(httpStatus, null, "PYTHON_PROTOCOL_INVALID", requestId);
                }
                try {
                    if (httpStatus == 200 || httpStatus == 202) {
                        PythonCharacterTask state = kind == PythonExtractionKind.AUDIO_EXTRACTION
                                ? audioState(body) : mapper.readValue(body, PythonCharacterTask.class);
                        checkState(id, state);
                        return new Reply(httpStatus, state, null, requestId);
                    }
                    JsonNode error = mapper.readTree(body);
                    String code = error == null ? null : error.path("detail").path("code").asText();
                    return new Reply(httpStatus, null, safeCode(code), requestId);
                } catch (JsonProcessingException | CharacterFailure exception) {
                    return new Reply(httpStatus, null, "PYTHON_PROTOCOL_INVALID", requestId);
                }
            });
        } catch (ResourceAccessException exception) {
            String code = exception.getMostSpecificCause() instanceof SocketTimeoutException
                    ? "PYTHON_HTTP_TIMEOUT" : "PYTHON_UNAVAILABLE";
            reply = new Reply(0, null, code, null);
        }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String status = reply.state() == null ? null : reply.state().status().name();
        String error = reply.state() != null && reply.state().error() != null
                ? safeCode(reply.state().error().code()) : reply.errorCode();
        if (reply.transientFailure()) {
            LOG.warn("Python 提取{}异常 taskId={} extractType={} attempt={} httpStatus={} durationMs={} "
                            + "errorCode={} requestId={}",
                    operation, id, kind, attempt, reply.httpStatus(), duration, error, reply.requestId());
        } else {
            LOG.info("Python 提取{} taskId={} extractType={} attempt={} httpStatus={} durationMs={} "
                            + "status={} errorCode={} requestId={}",
                    operation, id, kind, attempt, reply.httpStatus(), duration, status, error, reply.requestId());
        }
        return reply;
    }

    private PythonCharacterTask audioState(byte[] body) throws IOException {
        AudioTask task = mapper.readValue(body, AudioTask.class);
        if (task == null || task.branches() == null || !task.branches().containsKey("AUDIO")) {
            throw new CharacterFailure("PYTHON_PROTOCOL_INVALID");
        }
        AudioBranch branch = task.branches().get("AUDIO");
        if (branch == null) {
            throw new CharacterFailure("PYTHON_PROTOCOL_INVALID");
        }
        PythonCharacterTask.Result result = branch.result() == null ? null
                : new PythonCharacterTask.Result(branch.result().audioRef(), 1);
        return new PythonCharacterTask(task.taskId(), branch.status(), branch.stage(),
                new PythonCharacterTask.Progress(null, null), result, branch.error());
    }

    private record AudioSubmit(String taskId, String sourceVideoRef, List<String> outputs) {
    }

    private record AudioTask(String taskId, Map<String, AudioBranch> branches) {
    }

    private record AudioBranch(
            PythonCharacterTask.Status status, PythonCharacterTask.Stage stage,
            AudioResult result, PythonCharacterTask.Error error
    ) {
    }

    private record AudioResult(String audioRef) {
    }

    private static void checkState(long id, PythonCharacterTask state) {
        if (state == null || !Long.toString(id).equals(state.taskId()) || state.status() == null
                || state.progress() == null || negative(state.progress().frameCount())
                || negative(state.progress().detectedCharacterCount())) {
            throw new CharacterFailure("PYTHON_PROTOCOL_INVALID");
        }
        boolean succeeded = state.status() == PythonCharacterTask.Status.SUCCEEDED;
        boolean failed = state.status() == PythonCharacterTask.Status.FAILED;
        if (succeeded != (state.result() != null) || failed != (state.error() != null)) {
            throw new CharacterFailure("PYTHON_PROTOCOL_INVALID");
        }
        PythonCharacterTask.Result result = state.result();
        if (result != null && (result.characterAssetCount() == null || negative(result.characterAssetCount())
                || result.manifestRef() == null || result.manifestRef().isBlank()
                || result.manifestRef().length() > 512)) {
            throw new CharacterFailure("PYTHON_PROTOCOL_INVALID");
        }
    }

    private static boolean negative(Integer number) {
        return number != null && number < 0;
    }

    static String safeCode(String code) {
        return code != null && ERROR_CODE.matcher(code).matches() ? code : "PYTHON_PROTOCOL_INVALID";
    }

    record Reply(int httpStatus, PythonCharacterTask state, String errorCode, String requestId) {
        boolean transientFailure() {
            return httpStatus == 0 || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
        }

        boolean unknownTask() {
            return httpStatus == 404 && "TASK_NOT_FOUND".equals(errorCode);
        }
    }
}
