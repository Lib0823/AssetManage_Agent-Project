package com.inbeom.apiserver.realtime;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 단일 상향 KIS 실시간 WebSocket 연결 (시세 앱키, 평문 프레임).
 *
 * <p>역할:
 * <ul>
 *   <li>{@link UpstreamSubscriber} 구현 — {@link SubscriptionManager} 의 0→1/1→0 전이에서 등록/해제.</li>
 *   <li>lazy connect — 첫 구독(register) 시 연결. 부팅 시에는 연결하지 않는다.</li>
 *   <li>approval_key 선발급({@link KisApprovalKeyProvider}), 구독/해제 프레임 송신.</li>
 *   <li>PINGPONG 프레임 그대로 echo(keepalive).</li>
 *   <li>데이터 프레임 → {@link KisFrameParser} → {@link SubscriptionManager#fanOut}.</li>
 *   <li>비정상 종료 시 지수 backoff(+jitter, max 30s) 재연결 → approval 재발급 + 모든 활성 SubKey 재등록
 *       + 브라우저에 reconnecting/reconnected 상태 push.</li>
 * </ul>
 *
 * <p>자격증명은 {@link ConnectionCredentials} 값객체로 주입받는다(Phase 1=quote 키 1세트).
 * Phase 2(체결통보, 사용자키+AES)에서 사용자별 연결을 추가할 때 본 클래스를 자격증명만 달리해
 * 재사용할 수 있도록 외부화했다.
 *
 * <p>{@code @ConditionalOnProperty(kis.realtime.enabled)} 로, 비활성 시 본 빈은 생성되지 않는다
 * → {@link SubscriptionManager} 의 {@code @Lazy UpstreamSubscriber} 는 {@link NoopUpstreamSubscriber}
 * 로 폴백(graceful degrade, 부팅 무영향).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.realtime.enabled", havingValue = "true")
public class KisRealtimeUpstreamClient extends TextWebSocketHandler
        implements UpstreamSubscriber, SmartLifecycle {

    private final KisApprovalKeyProvider approvalKeyProvider;
    private final KisFrameParser frameParser;
    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper;
    private final String wsUrl;

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kis-realtime-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<WebSocketSession> upstreamSession = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile int reconnectAttempts = 0;

    private static final long BASE_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    public KisRealtimeUpstreamClient(
            KisApprovalKeyProvider approvalKeyProvider,
            KisFrameParser frameParser,
            SubscriptionManager subscriptionManager,
            ObjectMapper objectMapper,
            @Value("${kis.realtime.ws-url:ws://ops.koreainvestment.com:21000}") String wsUrl) {
        this.approvalKeyProvider = approvalKeyProvider;
        this.frameParser = frameParser;
        this.subscriptionManager = subscriptionManager;
        this.objectMapper = objectMapper;
        this.wsUrl = wsUrl;
    }

    // ── SmartLifecycle ──────────────────────────────────────────────────────

    @Override
    public void start() {
        running.set(true);
        log.info("[realtime] upstream client started (lazy connect on first subscription), ws-url={}", wsUrl);
    }

    @Override
    public void stop() {
        running.set(false);
        closeUpstream();
        log.info("[realtime] upstream client stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** REST/매매 등 다른 빈 기동 후 마지막에 시작. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    // ── UpstreamSubscriber ──────────────────────────────────────────────────

    @Override
    public void register(SubKey key) {
        ensureConnected();
        sendSubscriptionFrame(key, RealtimeTr.TR_TYPE_REGISTER);
    }

    @Override
    public void unregister(SubKey key) {
        sendSubscriptionFrame(key, RealtimeTr.TR_TYPE_UNREGISTER);
    }

    @Override
    public void registerAll(Collection<SubKey> keys) {
        for (SubKey key : keys) {
            sendSubscriptionFrame(key, RealtimeTr.TR_TYPE_REGISTER);
        }
    }

    // ── 연결 ─────────────────────────────────────────────────────────────────

    private void ensureConnected() {
        WebSocketSession s = upstreamSession.get();
        if (s != null && s.isOpen()) {
            return;
        }
        connect();
    }

    private synchronized void connect() {
        WebSocketSession existing = upstreamSession.get();
        if (existing != null && existing.isOpen()) {
            return;
        }
        if (!running.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            ConnectionCredentials creds = approvalKeyProvider.quoteCredentials();
            if (creds == null || !creds.isUsable()) {
                log.warn("[realtime] quote credentials not configured; upstream connect skipped (degrade)");
                subscriptionManager.broadcastStatus("disabled",
                        "실시간 시세가 연동되지 않았습니다 (실전 KIS 키 필요)");
                return;
            }
            String approvalKey = approvalKeyProvider.getApprovalKey(creds);
            if (approvalKey == null) {
                log.warn("[realtime] approval_key unavailable; upstream connect skipped (degrade)");
                subscriptionManager.broadcastStatus("disabled",
                        "실시간 시세 접속키 발급에 실패했습니다");
                return;
            }
            log.info("[realtime] connecting upstream {} ...", wsUrl);
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            WebSocketSession session = client.execute(this, headers, URI.create(wsUrl)).get();
            upstreamSession.set(session);
            reconnectAttempts = 0;
            log.info("[realtime] upstream connected: session={}", session.getId());
        } catch (Exception e) {
            log.warn("[realtime] upstream connect failed: {}", e.getMessage());
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    // ── WebSocketHandler 콜백 ─────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        upstreamSession.set(session);
        // 재연결이면 활성 SubKey 전체 재등록 + 클라이언트 알림.
        Collection<SubKey> active = subscriptionManager.activeSubKeys();
        if (!active.isEmpty()) {
            log.info("[realtime] re-registering {} active sub-keys after (re)connect", active.size());
            registerAll(active);
        }
        if (reconnectAttempts > 0) {
            subscriptionManager.broadcastStatus("reconnected", null);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }
        // PINGPONG: header.tr_id == "PINGPONG" 인 JSON 프레임 → 그대로 echo (keepalive).
        if (payload.charAt(0) == '{') {
            handleJsonFrame(session, payload);
            return;
        }
        // 데이터 프레임(평문). 파서가 PINGPONG/암호/미지원은 null 반환.
        try {
            KisFrameParser.ParseResult result = frameParser.parse(payload);
            if (result == null) {
                return;
            }
            RealtimeTr tr = RealtimeTr.fromTrId(result.trId());
            if (result.quote() != null) {
                subscriptionManager.fanOut(tr, result.symbol(), result.quote());
            } else if (result.tick() != null) {
                subscriptionManager.fanOut(tr, result.symbol(), result.tick());
            }
        } catch (Exception e) {
            log.debug("[realtime] frame parse error: {}", e.getMessage());
        }
    }

    private void handleJsonFrame(WebSocketSession session, String payload) {
        try {
            var node = objectMapper.readTree(payload);
            var header = node.get("header");
            String trId = (header != null && header.get("tr_id") != null)
                    ? header.get("tr_id").asString() : null;
            if (KisFrameParser.TR_PINGPONG.equals(trId)) {
                // keepalive: 받은 PINGPONG 프레임을 그대로 되돌려준다.
                session.sendMessage(new TextMessage(payload));
                return;
            }
            // 구독/해제 ACK (body.rt_cd 등). 진단용 로깅만.
            var body = node.get("body");
            if (body != null) {
                String rtCd = body.get("rt_cd") != null ? body.get("rt_cd").asString() : null;
                String msg = body.get("msg1") != null ? body.get("msg1").asString() : null;
                log.debug("[realtime] ACK tr_id={} rt_cd={} msg={}", trId, rtCd, msg);
            }
        } catch (Exception e) {
            log.debug("[realtime] JSON frame handling error: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[realtime] upstream transport error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        upstreamSession.compareAndSet(session, null);
        log.warn("[realtime] upstream connection closed: {} {}", status.getCode(), status.getReason());
        if (running.get()) {
            scheduleReconnect();
        }
    }

    // ── 재연결 (지수 backoff + jitter) ────────────────────────────────────────

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        // 재구독 대상이 없으면(모든 세션 이탈) 굳이 재연결하지 않는다.
        if (subscriptionManager.activeSubKeys().isEmpty()) {
            log.info("[realtime] no active subscriptions; skip reconnect");
            return;
        }
        int attempt = ++reconnectAttempts;
        long backoff = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt - 1, 5)));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, backoff / 2));
        long delay = backoff + jitter;
        log.info("[realtime] scheduling reconnect attempt #{} in {}ms", attempt, delay);
        subscriptionManager.broadcastStatus("reconnecting", null);
        scheduler.schedule(() -> {
            // 재연결 시 approval_key 재발급(만료/무효 대비).
            approvalKeyProvider.refreshApprovalKey(approvalKeyProvider.quoteCredentials());
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ── 구독 프레임 송신 ──────────────────────────────────────────────────────

    /**
     * 구독/해제 프레임 송신:
     * {@code {"header":{"approval_key":k,"custtype":"P","tr_type":"1|2","content-type":"utf-8"},
     *          "body":{"input":{"tr_id":<trId>,"tr_key":<trKey>}}}}.
     */
    private void sendSubscriptionFrame(SubKey key, String trType) {
        WebSocketSession session = upstreamSession.get();
        if (session == null || !session.isOpen()) {
            log.debug("[realtime] upstream not connected; defer {} {}", trType, key);
            return;
        }
        String approvalKey = approvalKeyProvider.getApprovalKey(approvalKeyProvider.quoteCredentials());
        if (approvalKey == null) {
            log.warn("[realtime] approval_key null; cannot send subscription frame for {}", key);
            return;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode header = root.putObject("header");
            header.put("approval_key", approvalKey);
            header.put("custtype", "P");
            header.put("tr_type", trType);
            header.put("content-type", "utf-8");
            ObjectNode input = root.putObject("body").putObject("input");
            input.put("tr_id", key.tr().trId());
            input.put("tr_key", key.trKey());

            String frame = objectMapper.writeValueAsString(root);
            session.sendMessage(new TextMessage(frame));
            log.debug("[realtime] sent tr_type={} tr_id={} tr_key={}", trType, key.tr().trId(), key.trKey());
        } catch (Exception e) {
            log.warn("[realtime] subscription frame send failed for {}: {}", key, e.getMessage());
        }
    }

    private void closeUpstream() {
        WebSocketSession s = upstreamSession.getAndSet(null);
        if (s != null && s.isOpen()) {
            try {
                s.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.debug("[realtime] upstream close error: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        closeUpstream();
        scheduler.shutdownNow();
        log.info("[realtime] upstream client shut down");
    }
}
