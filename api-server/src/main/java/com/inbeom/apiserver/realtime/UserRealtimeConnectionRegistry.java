package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.dto.realtime.FillMessage;
import com.inbeom.apiserver.dto.realtime.StatusMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 유저당 체결통보 상향 연결의 refcount/멀티플렉싱 레지스트리 (thread-safe).
 *
 * <p>Phase 1 {@code SubscriptionManager}(종목 단위 시세)와 별개로, 체결통보는 <b>사용자 계좌 단위</b>
 * 이벤트라 userId 를 키로 관리한다:
 * <ul>
 *   <li>{@code userId → UserFillsUpstreamConnection}: 사용자당 상향 연결 1개.</li>
 *   <li>{@code userId → Set<session>}: 그 사용자의 브라우저 세션들 (다탭/다기기).</li>
 *   <li>0→1 전이에서 연결 open(factory), 1→0 에서 close.</li>
 *   <li>상향 fill → 해당 userId 의 모든 세션에 fan-out.</li>
 * </ul>
 *
 * <p>JSR-356 세션은 동시 송신 불가 → 세션별 모니터로 직렬화한다.
 * {@code @ConditionalOnProperty(kis.realtime.fills.enabled)} — 비활성 시 빈 미생성(graceful degrade,
 * 핸들러는 null 체크로 안내). Phase 1 무영향.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.realtime.fills.enabled", havingValue = "true")
public class UserRealtimeConnectionRegistry {

    private final UserFillsConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    /** userId → 상향 체결통보 연결. */
    private final Map<Long, UserFillsUpstreamConnection> connections = new ConcurrentHashMap<>();
    /** userId → 구독 세션 집합. */
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    /** session → userId 역맵 (세션 종료 시 정리). */
    private final Map<WebSocketSession, Long> sessionUser = new ConcurrentHashMap<>();
    /** 세션별 송신 직렬화 모니터. */
    private final Map<WebSocketSession, Object> sendLocks = new ConcurrentHashMap<>();

    public UserRealtimeConnectionRegistry(UserFillsConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 체결통보 구독. 해당 userId 의 첫 세션이면 상향 연결을 open 한다.
     * 자격증명/htsId 미비 시 status:error 통지만 하고 연결을 만들지 않는다(degrade).
     */
    public void subscribeFills(WebSocketSession session, Long userId) {
        if (session == null || userId == null) {
            if (session != null) {
                sendStatus(session, "error", "체결통보 사용자 식별 실패");
            }
            return;
        }
        sendLocks.computeIfAbsent(session, s -> new Object());
        sessionUser.put(session, userId);

        Set<WebSocketSession> sessions =
                userSessions.computeIfAbsent(userId, u -> ConcurrentHashMap.newKeySet());

        boolean firstSession;
        synchronized (sessions) {
            firstSession = sessions.isEmpty();
            sessions.add(session);
        }

        if (firstSession) {
            openConnection(userId);
        }
        sendStatus(session, "fills-subscribed", null);
    }

    private synchronized void openConnection(Long userId) {
        if (connections.containsKey(userId)) {
            return;
        }
        try {
            UserFillsConnectionFactory.Result result = connectionFactory.create(
                    userId,
                    this::fanOutFill,
                    (state, notice) -> broadcastStatusToUser(userId, state, notice));
            if (!result.isOk()) {
                log.info("[fills] userId={} connection not created: {}", userId, result.errorNotice());
                broadcastStatusToUser(userId, "error", result.errorNotice());
                return;
            }
            UserFillsUpstreamConnection conn = result.connection();
            connections.put(userId, conn);
            conn.open();
            log.info("[fills] userId={} upstream connection opened (0→1)", userId);
        } catch (Exception e) {
            // degrade: 상향 연결 실패가 브라우저 세션/부팅에 영향 주지 않게 한다.
            log.warn("[fills] userId={} open connection failed: {}", userId, e.getMessage());
            broadcastStatusToUser(userId, "error", "체결통보 연결에 실패했습니다");
        }
    }

    /**
     * 체결통보 구독 해제. 해당 userId 의 마지막 세션이면 상향 연결을 close 한다.
     */
    public void unsubscribeFills(WebSocketSession session, Long userId) {
        if (session == null || userId == null) {
            return;
        }
        boolean lastSession = removeSessionFromUser(session, userId);
        sendStatus(session, "fills-unsubscribed", null);
        if (lastSession) {
            closeConnection(userId);
        }
    }

    /**
     * 세션 종료 시 정리. 역맵으로 userId 를 찾아 구독 해제하고, 마지막이면 상향 연결 close.
     */
    public void removeSession(WebSocketSession session) {
        if (session == null) {
            return;
        }
        Long userId = sessionUser.remove(session);
        sendLocks.remove(session);
        if (userId == null) {
            return;
        }
        boolean lastSession = removeSessionFromUser(session, userId);
        if (lastSession) {
            closeConnection(userId);
        }
    }

    private boolean removeSessionFromUser(WebSocketSession session, Long userId) {
        sessionUser.remove(session, userId);
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null) {
            return false;
        }
        boolean nowEmpty;
        synchronized (sessions) {
            sessions.remove(session);
            nowEmpty = sessions.isEmpty();
            if (nowEmpty) {
                userSessions.remove(userId);
            }
        }
        return nowEmpty;
    }

    private synchronized void closeConnection(Long userId) {
        UserFillsUpstreamConnection conn = connections.remove(userId);
        if (conn != null) {
            try {
                conn.close();
                log.info("[fills] userId={} upstream connection closed (1→0)", userId);
            } catch (Exception e) {
                log.debug("[fills] userId={} close error: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * 상향에서 받은 체결을 해당 userId 의 모든 세션에 fan-out.
     */
    public void fanOutFill(Long userId, FillMessage message) {
        if (userId == null || message == null) {
            return;
        }
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("[fills] userId={} fan-out serialize failed: {}", userId, e.getMessage());
            return;
        }
        TextMessage frame = new TextMessage(json);
        for (WebSocketSession s : new ArrayList<>(sessions)) {
            send(s, frame);
        }
    }

    // ── 상태/송신 ─────────────────────────────────────────────────────────────

    private void broadcastStatusToUser(Long userId, String state, String notice) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession s : new ArrayList<>(sessions)) {
            sendStatus(s, state, notice);
        }
    }

    private void sendStatus(WebSocketSession session, String state, String notice) {
        try {
            send(session, new TextMessage(objectMapper.writeValueAsString(StatusMessage.of(state, notice))));
        } catch (Exception e) {
            log.debug("[fills] sendStatus failed: {}", e.getMessage());
        }
    }

    private void send(WebSocketSession session, TextMessage frame) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Object lock = sendLocks.computeIfAbsent(session, s -> new Object());
        synchronized (lock) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(frame);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("[fills] send failed to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<Long, UserFillsUpstreamConnection> e : connections.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                log.debug("[fills] shutdown close error userId={}: {}", e.getKey(), ex.getMessage());
            }
        }
        connections.clear();
        userSessions.clear();
        sessionUser.clear();
        sendLocks.clear();
        log.info("[fills] connection registry shut down");
    }
}
