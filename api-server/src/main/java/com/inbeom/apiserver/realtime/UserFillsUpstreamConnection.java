package com.inbeom.apiserver.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.inbeom.apiserver.dto.realtime.FillMessage;

/**
 * <b>유저당</b> KIS 체결통보 상향 WebSocket 연결 (사용자 계좌키 + 모의 trade 도메인 + AES 암호 프레임).
 *
 * <p>Phase 1 의 공유 시세 연결({@code KisRealtimeUpstreamClient})과 달리, 체결통보는 사용자 계좌별
 * 자격증명(appkey/secret)과 HTS ID(tr_key)가 필요하므로 <b>독립 클래스로 자체 구현</b>한다
 * (Phase 1 의 connect/재연결/PINGPONG 패턴을 참고해 새로 작성, 일부 중복 허용 — Phase 1 보존).
 *
 * <p>US 동등화: 한 연결에서 <b>국내(KR_FILL)와 미국(US_FILL) 체결통보를 함께 구독</b>한다(별도 연결
 * 아님). 둘 다 같은 tr_key=htsId 를 쓰며, 구독 ACK 의 ekey/iv 는 <b>tr_id 별로 각각</b> 캡처한다
 * (TR 마다 ACK 가 자기 키를 실어 옴 → {@code aesKeysByTr} 맵). flag 1 데이터 프레임은 그 프레임의
 * tr_id 로 KR/US 를 분기해 해당 키로 복호한 뒤 {@link FillFrameParser} 에 시장 정보와 함께 위임한다.
 *
 * <p>흐름:
 * <ul>
 *   <li>approval: {@code POST {approvalBaseUrl}/oauth2/Approval} (사용자 appkey/secret, body 필드명
 *       {@code secretkey}=Phase 1 {@code KisApprovalKeyProvider.FIELD_SECRET_KEY} 동일).</li>
 *   <li>connect → 구독 프레임 송신 (KR_FILL + US_FILL 각각, tr_key=htsId, tr_type=1).</li>
 *   <li>구독 ACK(JSON) body.output.{key,iv} 를 ACK 헤더의 tr_id 별로 캡처 → {@code aesKeysByTr}.</li>
 *   <li>flag 1 데이터 프레임 → tr_id 로 KR/US 분기 → 해당 tr_id 의 key/iv 로 {@link KisFillFrameDecryptor}
 *       → {@link FillFrameParser#parse(String, RealtimeTr)} → {@link FillMessage} → {@code fanOut} 콜백.</li>
 *   <li>PINGPONG 그대로 echo (keepalive).</li>
 *   <li>비정상 종료 → 지수 backoff(+jitter, max 30s) 재연결 → approval 재발급 + 재구독 + ekey/iv 재캡처
 *       (stale 키 금지).</li>
 * </ul>
 *
 * <p>graceful degrade: approval/connect 실패는 예외를 전파하지 않고 onStatus(error) 통지만 한다
 * (부팅·다른 사용자 무영향). key/iv 는 절대 로깅하지 않는다.
 */
@Slf4j
public class UserFillsUpstreamConnection extends TextWebSocketHandler {

    private static final String TR_PINGPONG = "PINGPONG";

    private static final long BASE_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final Long userId;
    private final ConnectionCredentials credentials;
    /** 국내 체결통보 tr_id — H0STCNI0(실전)/H0STCNI9(모의). 팩토리가 환경별로 결정해 주입. */
    private final String trId;
    /** 미국 체결통보 tr_id — H0GSCNI0/H0GSCNI9. 주입된 KR trId 의 실전/모의 환경에 맞춰 파생. */
    private final String usTrId;
    private final String htsId;           // tr_key
    private final String wsUrl;
    private final KisFillFrameDecryptor decryptor;
    private final FillFrameParser fillFrameParser;
    private final ObjectMapper objectMapper;
    private final ApprovalKeyIssuer approvalKeyIssuer;

    /** fan-out 콜백: (userId, FillMessage) → 레지스트리. */
    private final BiConsumer<Long, FillMessage> fanOut;
    /** 상태 통지 콜백: (state, notice) → 레지스트리(해당 유저 세션 broadcast). nullable. */
    private final BiConsumer<String, String> onStatus;

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kis-fills-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<WebSocketSession> upstreamSession = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile int reconnectAttempts = 0;

    // AES 복호 키/iv (구독 ACK output 에서 캡처). TR(tr_id)별로 각각 보관 — KR_FILL/US_FILL 가
    // 각자 ACK 로 자기 키를 실어 오기 때문. 재연결 시 clear 후 재캡처(stale 금지).
    // key/iv 는 절대 로깅하지 않는다.
    private final Map<String, AesKeyIv> aesKeysByTr = new ConcurrentHashMap<>();

    /** tr_id 별 AES key/iv 한 쌍. */
    private record AesKeyIv(String key, String iv) {
    }

    /** approval 발급 함수 인터페이스 (테스트/팩토리 주입 가능, RestTemplate 호출 포함). */
    @FunctionalInterface
    public interface ApprovalKeyIssuer {
        /** 사용자 자격증명으로 approval_key 발급. 실패 시 null(예외 비전파). */
        String issue(ConnectionCredentials creds);
    }

    public UserFillsUpstreamConnection(
            Long userId,
            ConnectionCredentials credentials,
            String trId,
            String htsId,
            String wsUrl,
            KisFillFrameDecryptor decryptor,
            FillFrameParser fillFrameParser,
            ObjectMapper objectMapper,
            ApprovalKeyIssuer approvalKeyIssuer,
            BiConsumer<Long, FillMessage> fanOut,
            BiConsumer<String, String> onStatus) {
        this.userId = userId;
        this.credentials = credentials;
        this.trId = trId;
        // US 체결통보 tr_id 는 KR trId 의 실전/모의 환경에 맞춰 파생(KR 모의면 US 도 모의).
        boolean mock = trId != null && trId.equals(RealtimeTr.KR_FILL.mockTrId());
        this.usTrId = mock ? RealtimeTr.US_FILL.mockTrId() : RealtimeTr.US_FILL.trId();
        this.htsId = htsId;
        this.wsUrl = wsUrl;
        this.decryptor = decryptor;
        this.fillFrameParser = fillFrameParser;
        this.objectMapper = objectMapper;
        this.approvalKeyIssuer = approvalKeyIssuer;
        this.fanOut = fanOut;
        this.onStatus = onStatus;
    }

    // ── 수명주기 ──────────────────────────────────────────────────────────────

    /**
     * 연결 시작 + 구독. graceful degrade: 자격증명/htsId 미비 또는 approval/connect 실패 시
     * 예외 없이 status:error 통지만 하고 조용히 비활성 상태를 유지한다.
     */
    public synchronized void open() {
        running.set(true);
        connect();
    }

    /** 연결 종료 + 스케줄러 정리. 멱등. */
    public synchronized void close() {
        running.set(false);
        closeUpstream();
        scheduler.shutdownNow();
        log.info("[fills] connection closed for userId={}", userId);
    }

    public boolean isOpen() {
        WebSocketSession s = upstreamSession.get();
        return s != null && s.isOpen();
    }

    // ── 연결 ─────────────────────────────────────────────────────────────────

    private synchronized void connect() {
        WebSocketSession existing = upstreamSession.get();
        if (existing != null && existing.isOpen()) {
            return;
        }
        if (!running.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            if (credentials == null || !credentials.isUsable()) {
                log.warn("[fills] userId={} credentials not usable; connect skipped (degrade)", userId);
                notifyStatus("error", "체결통보 연동 자격증명이 없습니다 (KIS 계좌 키 필요)");
                return;
            }
            if (htsId == null || htsId.isBlank()) {
                log.warn("[fills] userId={} htsId blank; connect skipped (degrade)", userId);
                notifyStatus("error", "체결통보를 위해 HTS ID 설정이 필요합니다");
                return;
            }
            // 재연결마다 approval 재발급(만료/무효 대비) + ekey/iv 재캡처 위해 슬롯 초기화.
            aesKeysByTr.clear();
            String approvalKey = approvalKeyIssuer.issue(credentials);
            if (approvalKey == null) {
                log.warn("[fills] userId={} approval_key unavailable; connect skipped (degrade)", userId);
                notifyStatus("error", "체결통보 접속키 발급에 실패했습니다");
                return;
            }
            log.info("[fills] connecting upstream {} for userId={} ...", wsUrl, userId);
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            WebSocketSession session = client.execute(this, headers, URI.create(wsUrl)).get();
            upstreamSession.set(session);
            reconnectAttempts = 0;
            // 연결 직후 구독 프레임 송신 (tr_type=1) — 국내(KR_FILL) + 미국(US_FILL) 모두.
            sendSubscribe(session, approvalKey, trId);
            sendSubscribe(session, approvalKey, usTrId);
            log.info("[fills] upstream connected for userId={}: session={}", userId, session.getId());
        } catch (Exception e) {
            log.warn("[fills] userId={} connect failed: {}", userId, e.getMessage());
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    /** 구독 프레임 송신: tr_id=체결통보 TR(KR/US), tr_key=htsId, tr_type=1. */
    private void sendSubscribe(WebSocketSession session, String approvalKey, String subTrId) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode header = root.putObject("header");
            header.put("approval_key", approvalKey);
            header.put("custtype", "P");
            header.put("tr_type", RealtimeTr.TR_TYPE_REGISTER);
            header.put("content-type", "utf-8");
            ObjectNode input = root.putObject("body").putObject("input");
            input.put("tr_id", subTrId);
            input.put("tr_key", htsId);

            String frame = objectMapper.writeValueAsString(root);
            session.sendMessage(new TextMessage(frame));
            log.debug("[fills] sent subscribe tr_id={} (tr_key=htsId) for userId={}", subTrId, userId);
        } catch (Exception e) {
            log.warn("[fills] subscribe send failed for userId={}: {}", userId, e.getMessage());
        }
    }

    /** 데이터/ACK 프레임의 tr_id 로 체결통보 TR(KR_FILL/US_FILL) 판별. 미상이면 KR 폴백. */
    private RealtimeTr resolveFillTr(String frameTrId) {
        if (frameTrId != null
                && (frameTrId.equals(RealtimeTr.US_FILL.trId()) || frameTrId.equals(RealtimeTr.US_FILL.mockTrId()))) {
            return RealtimeTr.US_FILL;
        }
        return RealtimeTr.KR_FILL;
    }

    // ── WebSocketHandler 콜백 ─────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }
        // JSON 프레임: PINGPONG echo 또는 구독 ACK(ekey/iv 캡처).
        if (payload.charAt(0) == '{') {
            handleJsonFrame(session, payload);
            return;
        }
        // 데이터 프레임. 체결통보는 flag 1(AES 암호).
        try {
            FrameEnvelope.Parsed env = FrameEnvelope.split(payload);
            if (env == null) {
                return;
            }
            if (!env.isEncrypted()) {
                // 평문 프레임은 체결통보 경로에서 기대하지 않음 → 진단만.
                log.debug("[fills] non-encrypted frame (flag={}) tr_id={} ignored", env.flag(), env.trId());
                return;
            }
            AesKeyIv kv = aesKeysByTr.get(env.trId());
            if (kv == null) {
                log.warn("[fills] userId={} encrypted frame tr_id={} before ekey/iv captured; dropped",
                        userId, env.trId());
                return;
            }
            String decrypted = decryptor.decrypt(kv.key(), kv.iv(), env.data());
            if (decrypted == null) {
                return;
            }
            // 프레임 tr_id 로 KR/US 체결통보를 판별해 해당 필드 블록으로 파싱.
            FillMessage fill = fillFrameParser.parse(decrypted, resolveFillTr(env.trId()));
            if (fill != null) {
                fanOut.accept(userId, fill);
            }
        } catch (Exception e) {
            log.debug("[fills] userId={} frame handling error: {}", userId, e.getMessage());
        }
    }

    private void handleJsonFrame(WebSocketSession session, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode header = node.get("header");
            String frameTrId = (header != null && header.get("tr_id") != null)
                    ? header.get("tr_id").asString() : null;
            if (TR_PINGPONG.equals(frameTrId)) {
                session.sendMessage(new TextMessage(payload));
                return;
            }
            // 구독 ACK: body.output.{key,iv} 캡처 (AES 복호 슬롯). 값은 로깅하지 않는다.
            JsonNode body = node.get("body");
            if (body != null) {
                JsonNode output = body.get("output");
                if (output != null) {
                    String key = textOrNull(output, "key");
                    String iv = textOrNull(output, "iv");
                    if (key != null && iv != null && frameTrId != null) {
                        // tr_id 별로 키를 보관 (KR_FILL/US_FILL 가 각자 ACK 로 자기 키를 실어 옴).
                        aesKeysByTr.put(frameTrId, new AesKeyIv(key, iv));
                        log.info("[fills] userId={} ekey/iv captured from subscribe ACK tr_id={} (keyLen={}, ivLen={})",
                                userId, frameTrId, key.length(), iv.length());
                    }
                }
                String rtCd = textOrNull(body, "rt_cd");
                String msg = textOrNull(body, "msg1");
                log.debug("[fills] userId={} ACK tr_id={} rt_cd={} msg={}", userId, frameTrId, rtCd, msg);
            }
        } catch (Exception e) {
            log.debug("[fills] userId={} JSON frame handling error: {}", userId, e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[fills] userId={} transport error: {}", userId, exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        upstreamSession.compareAndSet(session, null);
        log.warn("[fills] userId={} upstream closed: {} {}", userId, status.getCode(), status.getReason());
        if (running.get()) {
            scheduleReconnect();
        }
    }

    // ── 재연결 (지수 backoff + jitter) ────────────────────────────────────────

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.warn("[fills] userId={} max reconnect attempts ({}) reached; giving up",
                    userId, MAX_RECONNECT_ATTEMPTS);
            notifyStatus("error", "체결통보 연결이 반복 실패하여 중단되었습니다");
            return;
        }
        int attempt = ++reconnectAttempts;
        long backoff = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt - 1, 5)));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, backoff / 2));
        long delay = backoff + jitter;
        log.info("[fills] userId={} scheduling reconnect attempt #{} in {}ms", userId, attempt, delay);
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[fills] userId={} reconnect schedule failed (shutting down?): {}", userId, e.getMessage());
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private void notifyStatus(String state, String notice) {
        if (onStatus != null) {
            try {
                onStatus.accept(state, notice);
            } catch (Exception e) {
                log.debug("[fills] userId={} status notify failed: {}", userId, e.getMessage());
            }
        }
    }

    private void closeUpstream() {
        WebSocketSession s = upstreamSession.getAndSet(null);
        if (s != null && s.isOpen()) {
            try {
                s.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.debug("[fills] userId={} upstream close error: {}", userId, e.getMessage());
            }
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }
}
