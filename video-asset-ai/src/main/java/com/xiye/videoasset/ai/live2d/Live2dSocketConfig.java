package com.xiye.videoasset.ai.live2d;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class Live2dSocketConfig implements WebSocketConfigurer {
    private final Live2dEventsHandler events;

    Live2dSocketConfig(Live2dEventsHandler events) {
        this.events = events;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(events, "/projects/{projectId}/live2d/tasks/{taskId}/events")
                .setAllowedOriginPatterns("*");
    }
}
