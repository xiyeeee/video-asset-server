package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
class Live2dPythonSocket implements WebSocket.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dPythonSocket.class);
    private final URI events;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Live2dMapper tasks;
    private final Live2dPython python;
    private final Live2dSync sync;
    private final ThreadPoolTaskScheduler scheduler;
    private final StringBuilder buffer = new StringBuilder();
    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicInteger generation = new AtomicInteger();
    private boolean dropping;
    private volatile boolean running = true;
    private volatile WebSocket socket;

    Live2dPythonSocket(
            ObjectMapper mapper, Live2dMapper tasks, Live2dPython python, Live2dSync sync,
            Live2dSettings settings, @Qualifier("live2dScheduler") ThreadPoolTaskScheduler scheduler
    ) {
        this.mapper = mapper;
        this.tasks = tasks;
        this.python = python;
        this.sync = sync;
        this.scheduler = scheduler;
        this.events = events(settings.pythonUrl());
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
    }

    @PostConstruct
    void start() {
        connect();
    }

    @PreDestroy
    void stop() {
        running = false;
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    private void connect() {
        if (!running) {
            return;
        }
        int epoch = generation.incrementAndGet();
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(events, this)
                .whenComplete((opened, error) -> {
                    if (!running || epoch != generation.get()) {
                        if (opened != null) {
                            opened.sendClose(WebSocket.NORMAL_CLOSURE, "replaced");
                        }
                        return;
                    }
                    if (error != null) {
                        LOG.warn("Python Live2D 事件连接失败");
                        scheduleReconnect();
                    }
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket previous = socket;
        socket = webSocket;
        attempt.set(0);
        if (previous != null && previous != webSocket) {
            previous.sendClose(WebSocket.NORMAL_CLOSURE, "replaced");
        }
        LOG.info("Python Live2D 事件已连接");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        String body = null;
        synchronized (buffer) {
            if (!dropping) {
                buffer.append(data);
                if (buffer.length() > 65_536) {
                    buffer.setLength(0);
                    dropping = true;
                }
            }
            if (last) {
                if (!dropping) {
                    body = buffer.toString();
                    buffer.setLength(0);
                }
                dropping = false;
            }
        }
        if (body != null) {
            accept(body);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (socket != webSocket) {
            return null;
        }
        socket = null;
        synchronized (buffer) {
            buffer.setLength(0);
            dropping = false;
        }
        if (running) {
            LOG.info("Python Live2D 事件连接关闭 status={}", statusCode);
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOG.warn("Python Live2D 事件连接异常 exceptionType={}", error.getClass().getSimpleName());
    }

    private void accept(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String taskId = node.path("taskId").asText("");
            if (taskId.length() > 128 || taskId.isBlank()) {
                return;
            }
            Live2dTask task = tasks.find(taskId);
            if (task == null) {
                return;
            }
            sync.apply(task, python.readEvent(body, task));
        } catch (IOException | Live2dFailure exception) {
            LOG.warn("忽略无法解析的 Live2D 状态帧");
        }
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        long delay = Math.min(30, attempt.incrementAndGet()) * 1000L;
        scheduler.schedule(this::connect, Instant.now().plusMillis(delay));
    }

    private static URI events(URI python) {
        String scheme = "https".equalsIgnoreCase(python.getScheme()) ? "wss" : "ws";
        String path = python.getRawPath() == null ? "" : python.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(scheme + "://" + python.getRawAuthority() + path + "/internal/v1/live2d/tasks/events");
    }
}
