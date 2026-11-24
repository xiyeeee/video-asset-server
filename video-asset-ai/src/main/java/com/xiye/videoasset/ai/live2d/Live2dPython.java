package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
class Live2dPython {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dPython.class);
    private static final String ENDPOINT = "/internal/v1/live2d/tasks";
    private static final Set<String> STAGES = Set.of("PREPARING", "GENERATING", "VALIDATING", "EXPORTING");
    private final RestClient client;
    private final ObjectMapper mapper;

    Live2dPython(RestClient.Builder builder, ObjectMapper mapper, Live2dSettings settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(settings.connectTimeout());
        factory.setReadTimeout(settings.readTimeout());
        this.client = builder.baseUrl(settings.pythonUrl().toString()).requestFactory(factory).build();
        this.mapper = mapper;
    }

    Reply get(Live2dTask task) {
        return exchange(task, "查询", client.get().uri(ENDPOINT + "/{id}", task.id()));
    }

    Snapshot readEvent(String body, Live2dTask task) {
        if (body == null || body.length() > 65_536) {
            throw new Live2dFailure("PYTHON_PROTOCOL_INVALID");
        }
        try {
            return validate(task, mapper.readValue(body, Snapshot.class));
        } catch (IOException exception) {
            throw new Live2dFailure("PYTHON_PROTOCOL_INVALID");
        }
    }

    Reply submit(Live2dTask task, String source) {
        Submit payload = new Submit(task.id(), Long.toString(task.projectId()), task.modelName(), source);
        return exchange(task, "提交", client.post().uri(ENDPOINT).contentType(MediaType.APPLICATION_JSON).body(payload));
    }

    private Reply exchange(Live2dTask task, String operation, RestClient.RequestHeadersSpec<?> request) {
        long start = System.nanoTime();
        Reply reply;
        try {
            reply = request.exchange((sent, received) -> {
                int status = received.getStatusCode().value();
                String requestId = safe(received.getHeaders().getFirst("X-Request-ID"), "[A-Za-z0-9_-]{1,128}");
                if (status == 408 || status == 429 || status >= 500) {
                    return new Reply(status, null, "PYTHON_UNAVAILABLE", requestId);
                }
                byte[] body = received.getBody().readNBytes(65_537);
                if (body.length > 65_536) {
                    return new Reply(status, null, "PYTHON_PROTOCOL_INVALID", requestId);
                }
                try {
                    if (status == 200 || status == 202) {
                        Snapshot snapshot = mapper.readValue(body, Snapshot.class);
                        return new Reply(status, validate(task, snapshot), null, requestId);
                    }
                    JsonNode error = mapper.readTree(body);
                    String code = error == null ? null : error.path("detail").path("code").asText();
                    return new Reply(status, null, errorCode(code), requestId);
                } catch (IOException | Live2dFailure failure) {
                    return new Reply(status, null, "PYTHON_PROTOCOL_INVALID", requestId);
                }
            });
        } catch (ResourceAccessException exception) {
            reply = new Reply(0, null, "PYTHON_UNAVAILABLE", null);
        }
        LOG.info("Python Live2D {} taskId={} projectId={} durationMs={} httpStatus={} status={} errorCode={} requestId={}",
                operation, task.id(), task.projectId(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                reply.httpStatus(), reply.snapshot() == null ? null : reply.snapshot().status(),
                reply.errorCode(), reply.requestId());
        return reply;
    }

    private static Snapshot validate(Live2dTask task, Snapshot value) {
        if (value == null || !task.id().equals(value.taskId()) || value.status() == null
                || (value.status() == Live2dTask.Status.FAILED) != (value.error() != null)
                || (value.status() == Live2dTask.Status.SUCCEEDED) != (value.result() != null)) {
            throw new Live2dFailure("PYTHON_PROTOCOL_INVALID");
        }
        if (value.result() != null && (!pathValue(value.result().manifestPath())
                || !pathValue(value.result().artifactRef()))) {
            throw new Live2dFailure("PYTHON_PROTOCOL_INVALID");
        }
        Failure error = value.error() == null ? null
                : new Failure(errorCode(value.error().code()), stage(value.error().stage()));
        return new Snapshot(value.taskId(), value.status(), stage(value.stage()), value.result(), error);
    }

    private static boolean pathValue(String value) {
        return value != null && !value.isBlank() && value.length() <= 512;
    }

    private static String stage(String value) {
        return value != null && STAGES.contains(value) ? value : null;
    }

    private static String safe(String value, String pattern) {
        return value != null && value.matches(pattern) ? value : null;
    }

    private static String errorCode(String code) {
        String value = safe(code, "[A-Z][A-Z0-9_]{0,79}");
        return value == null ? "PYTHON_PROTOCOL_INVALID" : value;
    }

    record Submit(String taskId, String projectId, String modelName, String sourceImageRef) {
    }

    record Snapshot(String taskId, Live2dTask.Status status, String stage, Artifact result, Failure error) {
    }

    record Artifact(String manifestPath, String artifactRef) {
    }

    record Failure(String code, String stage) {
    }

    record Reply(int httpStatus, Snapshot snapshot, String errorCode, String requestId) {
        boolean missing() {
            return httpStatus == 404 && "TASK_NOT_FOUND".equals(errorCode);
        }

        boolean transientFailure() {
            return httpStatus == 0 || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
        }
    }
}
