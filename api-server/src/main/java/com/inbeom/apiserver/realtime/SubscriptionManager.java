package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.dto.realtime.StatusMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실시간 구독 멀티플렉싱/refcount 관리 (thread-safe).
 *
 * <p>다수 브라우저 세션을 단일 상향 KIS 연결로 멀티플렉싱한다:
 * <ul>
 *   <li>{@code SubKey → Set<session>}: 구독자 집합. 0→1 전이에서 상향 register, 1→0 에서 unregister.</li>
 *   <li>{@code session → Set<SubKey>}: 역맵. 세션 종료 시 일괄 해제.</li>
 *   <li>상향 프레임 수신 → 해당 SubKey 의 모든 세션에 fan-out.</li>
 * </ul>
 *
 * <p>JSR-356 세션은 동시 송신 불가 → 세션별 모니터로 직렬화한다.
 * 컬렉션은 ConcurrentHashMap + 동기화 set 으로 보호한다.
 *
 * <p>{@link UpstreamSubscriber} 는 {@code @Lazy} 주입(↔ {@link KisRealtimeUpstreamClient} 순환 차단).
 * 상향 미연동/비활성이어도 refcount 와 세션 송신은 정상 동작(graceful degrade).
 */
@Slf4j
@Component
public class SubscriptionManager {

    private final UpstreamSubscriber upstream;
    private final ObjectMapper objectMapper;
    private final int maxSymbols;

    public SubscriptionManager(
            @Lazy UpstreamSubscriber upstream,
            ObjectMapper objectMapper,
            @Value("${kis.realtime.max-symbols:40}") int maxSymbols) {
        this.upstream = upstream;
        // 메시지 DTO 들은 클래스 레벨 @JsonInclude(NON_NULL) 로 null 필드를 직렬화에서 제외한다.
        this.objectMapper = objectMapper;
        this.maxSymbols = maxSymbols;
    }

    /** SubKey → 구독 세션 집합. */
    private final Map<SubKey, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    /** 세션 → 구독한 SubKey 집합 (역맵, 세션 종료 시 일괄 해제). */
    private final Map<WebSocketSession, Set<SubKey>> sessionKeys = new ConcurrentHashMap<>();

    /** 세션별 송신 직렬화 모니터 (JSR-356 동시송신 금지 대응). */
    private final Map<WebSocketSession, Object> sendLocks = new ConcurrentHashMap<>();

    /**
     * 세션 구독. 0→1 전이 시 상향 register. 구독상한 초과 시 상태 메시지로 거부.
     *
     * @return 구독 성공 여부 (상한 초과 시 false)
     */
    public boolean subscribe(WebSocketSession session, SubKey key) {
        sessionKeys.computeIfAbsent(session, s -> ConcurrentHashMap.newKeySet());
        sendLocks.computeIfAbsent(session, s -> new Object());

        // 상향 구독상한: 현재 활성 SubKey 수가 상한이고 신규 키면 거부.
        boolean isNewKey = !subscribers.containsKey(key);
        if (isNewKey && subscribers.size() >= maxSymbols) {
            log.warn("[realtime] subscription cap reached ({}); reject {}", maxSymbols, key);
            sendStatus(session, "limit",
                    "실시간 구독 상한(" + maxSymbols + ")에 도달했습니다");
            return false;
        }

        boolean firstSubscriber;
        Set<WebSocketSession> sessions = subscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        synchronized (sessions) {
            firstSubscriber = sessions.isEmpty();
            sessions.add(session);
        }
        sessionKeys.get(session).add(key);

        if (firstSubscriber) {
            log.debug("[realtime] 0→1 register {}", key);
            safeUpstream(() -> upstream.register(key));
        }
        return true;
    }

    /**
     * 세션 단일 구독 해제. 1→0 전이 시 상향 unregister.
     */
    public void unsubscribe(WebSocketSession session, SubKey key) {
        Set<WebSocketSession> sessions = subscribers.get(key);
        boolean lastSubscriber = false;
        if (sessions != null) {
            synchronized (sessions) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    subscribers.remove(key);
                    lastSubscriber = true;
                }
            }
        }
        Set<SubKey> keys = sessionKeys.get(session);
        if (keys != null) {
            keys.remove(key);
        }
        if (lastSubscriber) {
            log.debug("[realtime] 1→0 unregister {}", key);
            safeUpstream(() -> upstream.unregister(key));
        }
    }

    /**
     * 세션 종료 시 해당 세션의 모든 구독 해제 (1→0 전이는 상향 unregister).
     */
    public void removeSession(WebSocketSession session) {
        Set<SubKey> keys = sessionKeys.remove(session);
        sendLocks.remove(session);
        if (keys == null) {
            return;
        }
        for (SubKey key : new ArrayList<>(keys)) {
            unsubscribe(session, key);
        }
        log.debug("[realtime] session {} removed ({} keys released)", session.getId(), keys.size());
    }

    /**
     * 상향에서 받은 파싱 결과를 구독 세션들에 fan-out.
     */
    public void fanOut(RealtimeTr tr, String symbol, Object message) {
        if (tr == null || symbol == null || message == null) {
            return;
        }
        SubKey key = lookupKey(tr, symbol);
        if (key == null) {
            return;
        }
        Set<WebSocketSession> sessions = subscribers.get(key);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("[realtime] fan-out serialize failed for {}: {}", key, e.getMessage());
            return;
        }
        TextMessage frame = new TextMessage(json);
        for (WebSocketSession s : new ArrayList<>(sessions)) {
            send(s, frame);
        }
    }

    /**
     * 재연결 시 상향에 재등록할 현재 활성 SubKey 스냅샷.
     */
    public List<SubKey> activeSubKeys() {
        return new ArrayList<>(subscribers.keySet());
    }

    // ── 세션 송신 (직렬화) ───────────────────────────────────────────────────

    /**
     * 단일 세션에 상태 메시지 전송: {@code {type:"status", state:..., notice:...}}.
     */
    public void sendStatus(WebSocketSession session, String state, String notice) {
        try {
            send(session, new TextMessage(objectMapper.writeValueAsString(StatusMessage.of(state, notice))));
        } catch (Exception e) {
            log.debug("[realtime] sendStatus failed: {}", e.getMessage());
        }
    }

    /**
     * 모든 활성 세션에 상태 브로드캐스트 (재연결/disabled 등).
     */
    public void broadcastStatus(String state, String notice) {
        for (WebSocketSession s : new HashSet<>(sessionKeys.keySet())) {
            sendStatus(s, state, notice);
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
                log.debug("[realtime] send failed to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private SubKey lookupKey(RealtimeTr tr, String symbol) {
        // SubKey 의 trKey 는 국내=symbol, 미국=거래소접두 포함이라 fan-out 매칭은 (tr, symbol) 로 한다.
        // subscribers 키 집합은 작으므로(상한 ~40) 선형 조회로 충분.
        for (SubKey k : subscribers.keySet()) {
            if (k.tr() == tr && symbolMatches(k.symbol(), symbol)) {
                return k;
            }
        }
        return null;
    }

    private boolean symbolMatches(String subscribed, String incoming) {
        if (subscribed == null || incoming == null) {
            return false;
        }
        return subscribed.equalsIgnoreCase(incoming);
    }

    private void safeUpstream(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 상향 미연동/오류는 구독 자체(브라우저측)에 영향 주지 않음
            log.warn("[realtime] upstream action failed: {}", e.getMessage());
        }
    }
}
