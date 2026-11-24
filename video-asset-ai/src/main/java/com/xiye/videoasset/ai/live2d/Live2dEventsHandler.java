package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.common.BusinessException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
class Live2dEventsHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private static final Pattern PATH = Pattern.compile("^/projects/([1-9]\\d*)/live2d/tasks/([a-f0-9]{32})/events$");
    private final Live2dService service;
    private final Live2dHub hub;

    Live2dEventsHandler(Live2dService service, Live2dHub hub) {
        this.service = service;
        this.hub = hub;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("video-asset.bearer");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Matcher matcher = session.getUri() == null ? null : PATH.matcher(session.getUri().getPath());
        if (matcher == null || !matcher.matches()) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        long project = Long.parseLong(matcher.group(1));
        String taskId = matcher.group(2);
        if (!(session.getPrincipal() instanceof JwtAuthenticationToken authentication)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            service.query(authentication.getToken().getSubject(), project, taskId);
        } catch (BusinessException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        hub.add(project, taskId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        forget(session);
    }

    private void forget(WebSocketSession session) {
        Matcher matcher = session.getUri() == null ? null : PATH.matcher(session.getUri().getPath());
        if (matcher != null && matcher.matches()) {
            hub.remove(Long.parseLong(matcher.group(1)), matcher.group(2), session);
        }
    }
}
