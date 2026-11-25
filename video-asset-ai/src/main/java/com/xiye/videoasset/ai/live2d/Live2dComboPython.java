package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class Live2dComboPython {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dComboPython.class);
    private static final String COMPILE = "/internal/v1/live2d/combo-motions/compile";
    private static final String CAPABILITIES = "/internal/v1/live2d/motion-capabilities";
    private static final String MODEL_ANALYSIS = "/internal/v1/live2d/model-capabilities/analyze";
    private static final String TASK = "/internal/v1/live2d/motion-tasks/{taskId}";
    private final RestClient client;
    private final ObjectMapper json;
    private final Duration taskTimeout;

    Live2dComboPython(RestClient.Builder builder, ObjectMapper json, Live2dSettings settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.connectTimeout());
        // Python 首次编译请求最多等待 15 秒，较长编译再由 Java 按任务 ID 轮询。
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.baseUrl(settings.pythonUrl().toString()).requestFactory(factory).build();
        this.json = json;
        this.taskTimeout = settings.taskTimeout();
    }

    Capabilities capabilities(String modelVersionId, String manifestRef, String mocSha256) {
        try {
            Capabilities result = client.post().uri(CAPABILITIES)
                    .body(new ModelRef(modelVersionId, manifestRef, mocSha256)).retrieve().body(Capabilities.class);
            if (result == null || !modelVersionId.equals(result.modelVersionId())) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
            }
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 409) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            if (status == 404) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
            }
            if (status == 422) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
            LOG.warn("Live2D 模型参数能力查询失败 modelVersionId={} httpStatus={}", modelVersionId, status);
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("Live2D 参数能力查询失败 modelVersionId={} exceptionType={}", modelVersionId,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
        }
    }

    JsonNode analyzeModel(String modelVersionId, String manifestRef, String mocSha256) {
        try {
            JsonNode result = client.post().uri(MODEL_ANALYSIS)
                    .body(new ModelRef(modelVersionId, manifestRef, mocSha256)).retrieve().body(JsonNode.class);
            if (result == null || !modelVersionId.equals(result.path("modelVersionId").asText())) {
                throw new AnalysisFailure("MODEL_ANALYSIS_RESPONSE_INVALID", "Python 能力分析响应不匹配");
            }
            return result;
        } catch (RestClientResponseException exception) {
            String code = upstreamCode(exception);
            LOG.warn("Live2D 模型能力分析被拒绝 modelVersionId={} httpStatus={} upstreamCode={}", modelVersionId,
                    exception.getStatusCode().value(), code);
            throw new AnalysisFailure(code, "Python 模型能力分析失败");
        } catch (RuntimeException exception) {
            LOG.warn("Live2D 模型能力分析不可用 modelVersionId={} exceptionType={}", modelVersionId,
                    exception.getClass().getSimpleName());
            throw new AnalysisFailure("PYTHON_UNAVAILABLE", "Python 模型能力分析暂不可用");
        }
    }

    Reply compile(Request request) {
        long started = System.nanoTime();
        try {
            Reply result = client.post().uri(COMPILE).body(request).retrieve().body(Reply.class);
            if (result == null || !request.requestId().equals(result.requestId())) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
            }
            logComplete(request, result, started);
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 504) {
                Reply result = poll(request, started);
                logComplete(request, result, started);
                return result;
            }
            if (status == 409) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            if (status == 404) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
            }
            if (status == 422) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
            throw unavailable(request, exception);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(request, exception);
        }
    }

    private Reply poll(Request request, long started) {
        long deadline = System.nanoTime() + taskTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            MotionState state;
            try {
                state = client.get().uri(TASK, request.requestId()).retrieve().body(MotionState.class);
            } catch (RuntimeException exception) {
                throw unavailable(request, exception);
            }
            if (state == null || !request.requestId().equals(state.taskId())) {
                throw unavailable(request, new IllegalStateException("Python motion task response mismatch"));
            }
            if ("SUCCEEDED".equals(state.status())) {
                if (state.result() == null || !request.modelMocSha256().equals(state.result().modelMocSha256())) {
                    throw unavailable(request, new IllegalStateException("Python motion artifact version mismatch"));
                }
                ObjectNode manifest = timelineManifest(request);
                manifest.put("schemaVersion", 2);
                manifest.put("compileMode", "PARAMETER_CURVES");
                manifest.set("compiledMotion", json.valueToTree(state.result()));
                return new Reply(request.requestId(), 1, "READY", request.durationMs(), List.of(),
                        List.of(), manifest);
            }
            if ("FAILED".equals(state.status())) {
                List<Issue> issues = state.diagnostics().stream()
                        .map(diagnostic -> new Issue(diagnostic.code(), diagnostic.message(), diagnostic.clipIds()))
                        .toList();
                if (issues.isEmpty() && state.error() != null) {
                    issues = List.of(new Issue(state.error().code(), state.error().message(), List.of()));
                }
                List<String> missing = state.diagnostics().stream()
                        .filter(diagnostic -> "CAPABILITY_MISSING".equals(diagnostic.code()))
                        .map(Diagnostic::capabilityId).filter(value -> value != null && !value.isBlank())
                        .distinct().sorted().toList();
                ObjectNode manifest = timelineManifest(request);
                manifest.put("schemaVersion", 2);
                manifest.put("compileMode", "PARAMETER_CURVES");
                manifest.set("diagnostics", json.valueToTree(state.diagnostics()));
                return new Reply(request.requestId(), 1, "CONFLICT", request.durationMs(), issues, missing, manifest);
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
            }
        }
        LOG.warn("Live2D 组合动作编译轮询超时 requestId={} projectId={} modelVersionId={} elapsedMs={}",
                request.requestId(), request.projectId(), request.modelVersionId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
    }

    private ObjectNode timelineManifest(Request request) {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("projectId", Long.toString(request.projectId()));
        manifest.put("modelVersionId", request.modelVersionId());
        manifest.put("name", request.name());
        manifest.put("durationMs", request.durationMs());
        ArrayNode segments = manifest.putArray("segments");
        for (Segment segment : request.segments()) {
            segments.add(json.valueToTree(segment));
        }
        return manifest;
    }

    private void logComplete(Request request, Reply result, long started) {
        LOG.info("Live2D 组合动作编译完成 requestId={} projectId={} modelVersionId={} status={} durationMs={}",
                request.requestId(), request.projectId(), request.modelVersionId(), result.status(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private BusinessException unavailable(Request request, RuntimeException exception) {
        LOG.warn("Live2D 组合动作编译服务不可用 requestId={} projectId={} modelVersionId={} exceptionType={}",
                request.requestId(), request.projectId(), request.modelVersionId(),
                exception.getClass().getSimpleName());
        return new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
    }

    private String upstreamCode(RestClientResponseException exception) {
        try {
            JsonNode error = json.readTree(exception.getResponseBodyAsString());
            return error.path("detail").path("code").asText("UPSTREAM_ERROR");
        } catch (RuntimeException | java.io.IOException ignored) {
            return "UPSTREAM_ERROR";
        }
    }

    record ModelRef(String modelVersionId, String modelManifestRef, String modelMocSha256) {
    }

    record Parameter(String id, double min, double max, double defaultValue, boolean active,
                     boolean activeDecreasing, boolean activeIncreasing) {
    }

    record Capability(String id, boolean supported, List<String> parameterIds, List<String> missingParameterIds) {
    }

    record Capabilities(String modelVersionId, int mocVersion, String mocSha256, List<Parameter> parameters,
                        List<Capability> capabilities) {
    }

    record Request(String requestId, long projectId, String modelVersionId, String modelManifestRef,
                   String modelMocSha256, String name, long durationMs, List<Segment> segments) {
    }

    record Segment(String segmentId, String trackType, String sourceId, String sourceVersionId,
                   long startTimeMs, long durationMs, Long sourceStartMs, Long sourceDurationMs,
                   String presetId, String sourceRef, String sourceSha256) {
    }

    record Issue(String code, String message, List<String> segmentIds) {
    }

    record Diagnostic(String code, String message, List<String> clipIds, List<String> parameterIds,
                      String capabilityId, Long startMs, Long endMs) {
    }

    record MotionResult(int formatVersion, String format, long durationMs, String artifactRef,
                        String artifactSha256, String modelMocSha256) {
    }

    record MotionFailure(String code, String message, String stage) {
    }

    record MotionState(String taskId, String modelVersionId, String status, String stage, MotionResult result,
                       MotionFailure error, List<Diagnostic> diagnostics) {
        MotionState {
            diagnostics = diagnostics == null ? new ArrayList<>() : diagnostics;
        }
    }

    record Reply(String requestId, int compilerVersion, String status, long durationMs, List<Issue> issues,
                 List<String> missingCapabilities, JsonNode playbackManifest) {
    }

    static final class AnalysisFailure extends RuntimeException {
        private final String code;

        AnalysisFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
