package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.common.BusinessException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
class Live2dHub {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dHub.class);
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Live2dService service;

    Live2dHub(ObjectMapper mapper, Live2dService service) {
        this.mapper = mapper;
        this.service = service;
    }

    void add(long project, String taskId, WebSocketSession session) {
        String key = key(project, taskId);
        synchronized (lock(key)) {
            sessions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            send(key, session, service.view(project, taskId));
        }
    }

    void remove(long project, String taskId, WebSocketSession session) {
        String key = key(project, taskId);
        Set<WebSocketSession> current = sessions.get(key);
        if (current != null) {
            current.remove(session);
        }
    }

    void publish(long project, String taskId) {
        String key = key(project, taskId);
        Set<WebSocketSession> current = sessions.get(key);
        if (current == null || current.isEmpty()) {
            return;
        }
        synchronized (lock(key)) {
            Live2dTaskVO view;
            try {
                view = service.view(project, taskId);
            } catch (BusinessException exception) {
                return;
            }
            for (WebSocketSession session : Set.copyOf(current)) {
                send(key, session, view);
            }
        }
    }

    private void send(String key, WebSocketSession session, Live2dTaskVO view) {
        if (view == null || !session.isOpen()) {
            return;
        }
        try {
            String body = mapper.writeValueAsString(view);
            synchronized (session) {
                session.sendMessage(new TextMessage(body));
            }
        } catch (JsonProcessingException exception) {
            LOG.warn("Live2D 状态无法序列化 taskKey={}", key);
        } catch (IOException exception) {
            Set<WebSocketSession> current = sessions.get(key);
            if (current != null) {
                current.remove(session);
            }
        }
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, ignored -> new Object());
    }

    private static String key(long project, String taskId) {
        return project + ":" + taskId;
    }
}
