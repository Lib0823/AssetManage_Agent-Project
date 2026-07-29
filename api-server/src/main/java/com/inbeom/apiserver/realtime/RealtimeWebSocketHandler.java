package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.service.KisQuoteService;
import com.inbeom.apiserver.service.OverseasExchange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 브라우저 ↔ 서버 실시간 핸들러 ({@code /ws/realtime}).
 *
 * <p>inbound 메시지(JSON): {@code {"action":"subscribe|unsubscribe","market":"KR|US",
 * "symbol":"005930|AAPL","type":"orderbook|tick","exchange":"NAS|NYS|AMS"}}.
 * → {@link RealtimeTr} 해석 후 {@link SubscriptionManager} subscribe/unsubscribe.
 *
 * <p>연결 직후, 시세 비활성(quote disabled)이면 즉시 {@code {type:status,state:disabled,notice}} 를
 * 보내 프론트가 REST 스냅샷을 유지하도록 한다(graceful degrade). 연결 종료 시 세션의 모든 구독 해제.
 *
 * <p>Phase 2(체결통보): inbound {@code {"action":"subscribe|unsubscribe","type":"fills"}} 는
 * 종목/심볼 없는 <b>계좌 단위</b> 구독이라 symbol 가드보다 먼저 분기해 {@link UserRealtimeConnectionRegistry}
 * 로 라우팅한다. registry 는 {@code kis.realtime.fills.enabled} 비활성 시 빈 미생성 → null 이면
 * status:error 안내(graceful degrade).
 */
@Slf4j
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    /** 시세 미연동 안내 (KisQuoteClient.NOTICE_KIS_QUOTE 와 동일 문구). */
    public static final String NOTICE_QUOTE_DISABLED =
            "실시간 시세가 연동되지 않았습니다 (실전 KIS 키 필요)";

    /** 체결통보 비활성 안내 (kis.realtime.fills.enabled=false). */
    public static final String NOTICE_FILLS_DISABLED =
            "체결통보가 활성화되지 않았습니다";

    private final SubscriptionManager subscriptionManager;
    private final KisQuoteService kisQuoteService;
    private final ObjectMapper objectMapper;
    /** 체결통보 레지스트리. fills 비활성 시 null (graceful degrade). */
    @Nullable
    private final UserRealtimeConnectionRegistry fillsRegistry;

    public RealtimeWebSocketHandler(SubscriptionManager subscriptionManager,
                                    KisQuoteService kisQuoteService,
                                    ObjectMapper objectMapper,
                                    @Nullable UserRealtimeConnectionRegistry fillsRegistry) {
        this.subscriptionManager = subscriptionManager;
        this.kisQuoteService = kisQuoteService;
        this.objectMapper = objectMapper;
        this.fillsRegistry = fillsRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("[realtime] client connected: session={}", session.getId());
        if (!kisQuoteService.isQuoteEnabled()) {
            // 상향 시세 비활성 → 브라우저는 기존 REST 스냅샷 유지.
            subscriptionManager.sendStatus(session, "disabled", NOTICE_QUOTE_DISABLED);
        } else {
            subscriptionManager.sendStatus(session, "open", null);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String action = text(node, "action");
            String market = text(node, "market");
            String symbol = text(node, "symbol");
            String type = text(node, "type");
            String exchange = text(node, "exchange");

            // 체결통보(계좌 단위): symbol 가드보다 먼저 분기. type=="fills" 는 종목이 없다.
            if (type != null && "fills".equalsIgnoreCase(type.trim())) {
                handleFills(session, action);
                return;
            }

            if (action == null || symbol == null || symbol.isBlank()) {
                subscriptionManager.sendStatus(session, "error", "action/symbol 누락");
                return;
            }

            RealtimeTr tr = RealtimeTr.resolve(market, type);
            if (tr == null) {
                subscriptionManager.sendStatus(session, "error",
                        "지원하지 않는 market/type: " + market + "/" + type);
                return;
            }

            SubKey key = toSubKey(tr, symbol, exchange);

            switch (action.trim().toLowerCase()) {
                case "subscribe" -> {
                    boolean ok = subscriptionManager.subscribe(session, key);
                    if (ok) {
                        subscriptionManager.sendStatus(session, "subscribed", null);
                    }
                }
                case "unsubscribe" -> {
                    subscriptionManager.unsubscribe(session, key);
                    subscriptionManager.sendStatus(session, "unsubscribed", null);
                }
                default -> subscriptionManager.sendStatus(session, "error",
                        "알 수 없는 action: " + action);
            }
        } catch (Exception e) {
            log.debug("[realtime] inbound message error: {}", e.getMessage());
            subscriptionManager.sendStatus(session, "error", "잘못된 메시지 형식");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("[realtime] client transport error session={}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptionManager.removeSession(session);
        if (fillsRegistry != null) {
            fillsRegistry.removeSession(session);
        }
        log.debug("[realtime] client disconnected: session={} status={}", session.getId(), status.getCode());
    }

    /**
     * 체결통보 구독/해제 처리. fills 비활성(registry null) 시 안내만 하고 무시(graceful degrade).
     * userId 는 핸드셰이크 attributes({@link JwtHandshakeInterceptor#ATTR_USER_ID})에서 읽는다.
     */
    private void handleFills(WebSocketSession session, String action) {
        if (fillsRegistry == null) {
            subscriptionManager.sendStatus(session, "disabled", NOTICE_FILLS_DISABLED);
            return;
        }
        if (action == null) {
            subscriptionManager.sendStatus(session, "error", "action 누락 (fills)");
            return;
        }
        Long userId = resolveUserId(session);
        if (userId == null) {
            subscriptionManager.sendStatus(session, "error", "체결통보 사용자 식별 실패");
            return;
        }
        switch (action.trim().toLowerCase()) {
            case "subscribe" -> fillsRegistry.subscribeFills(session, userId);
            case "unsubscribe" -> fillsRegistry.unsubscribeFills(session, userId);
            default -> subscriptionManager.sendStatus(session, "error",
                    "알 수 없는 action: " + action);
        }
    }

    /** 핸드셰이크에서 담긴 userId attribute → Long (JJWT claim 은 Integer/Long 둘 다 가능). */
    private Long resolveUserId(WebSocketSession session) {
        Object raw = session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER_ID);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * (TR, symbol, exchange) → SubKey. 국내는 tr_key=symbol, 미국은 {@link OverseasTrKey} 포맷.
     */
    private SubKey toSubKey(RealtimeTr tr, String symbol, String exchange) {
        String sym = symbol.trim().toUpperCase();
        if (tr.market() == RealtimeTr.Market.US) {
            OverseasExchange ex = OverseasExchange.fromCode(exchange);
            String trKey = OverseasTrKey.build(ex, sym);
            return new SubKey(tr, sym, trKey);
        }
        // 국내: 종목코드 원본(대문자 변환 무의미하지만 일관성 유지)을 tr_key 로.
        return new SubKey(tr, symbol.trim(), symbol.trim());
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }
}
