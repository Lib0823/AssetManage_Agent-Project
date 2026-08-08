package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.dto.realtime.QuoteMessage;
import com.inbeom.apiserver.dto.realtime.TickMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link KisRealtimeUpstreamClient} 단위 테스트 (실제 소켓 I/O 없음).
 *
 * <p>이 클래스는 {@code TextWebSocketHandler} 를 상속하고 자기 자신을 핸들러로 넘겨 접속하므로,
 * 상향 세션은 핸들러 콜백 {@code afterConnectionEstablished(session)} 로 mock {@link WebSocketSession}
 * 을 주입해 "연결된 상태"를 만든다. 접속 시도 경로는 ws-url 을 비 ws 스킴으로 두어
 * {@code StandardWebSocketClient} 가 네트워크에 나가기 전에 즉시 실패하도록 만든다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KisRealtimeUpstreamClient 단위 테스트")
class KisRealtimeUpstreamClientTest {

    /** 비 ws 스킴 → StandardWebSocketClient 가 소켓을 열기 전에 IllegalArgumentException. */
    private static final String UNREACHABLE_WS_URL = "http://localhost:0/never-dialed";

    @Mock
    private KisApprovalKeyProvider approvalKeyProvider;

    @Mock
    private KisFrameParser frameParser;

    @Mock
    private SubscriptionManager subscriptionManager;

    private KisRealtimeUpstreamClient client;

    private final ConnectionCredentials usableCreds =
            new ConnectionCredentials("app-key", "app-secret", "https://openapi.example.com:9443");

    private final SubKey samsungTick = new SubKey(RealtimeTr.KR_TICK, "005930", "005930");
    private final SubKey appleTick = new SubKey(RealtimeTr.US_TICK, "AAPL", "DNASAAPL");

    @BeforeEach
    void setUp() {
        client = new KisRealtimeUpstreamClient(
                approvalKeyProvider, frameParser, subscriptionManager,
                new ObjectMapper(), UNREACHABLE_WS_URL);
    }

    @AfterEach
    void tearDown() {
        // running=false + scheduler 종료 → 백그라운드 재연결 태스크가 테스트 밖으로 새지 않게 한다.
        client.shutdown();
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.isOpen()).thenReturn(true);
        return s;
    }

    /** approval_key 가 정상 발급되는 상태로 스텁. */
    private void givenApprovalKeyAvailable() {
        lenient().when(approvalKeyProvider.quoteCredentials()).thenReturn(usableCreds);
        lenient().when(approvalKeyProvider.getApprovalKey(any())).thenReturn("APPROVAL-KEY");
    }

    /** 상향 세션이 연결된 상태를 만든다 (activeSubKeys 기본값=빈 목록 → 재등록 없음). */
    private WebSocketSession connectedSession(String id) {
        WebSocketSession session = openSession(id);
        client.start();
        client.afterConnectionEstablished(session);
        return session;
    }

    private String captureSentPayload(WebSocketSession session) throws IOException {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    // ── SmartLifecycle ──────────────────────────────────────────────────────

    @Test
    @DisplayName("start() 는 러닝 상태로 만들 뿐 상향에 접속하지 않는다 (lazy connect)")
    void startDoesNotConnect() {
        // When
        client.start();

        // Then
        assertThat(client.isRunning()).isTrue();
        verify(approvalKeyProvider, never()).getApprovalKey(any());
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    @Test
    @DisplayName("getPhase() 는 Integer.MAX_VALUE — 다른 빈 기동 후 마지막에 시작")
    void phaseIsLast() {
        assertThat(client.getPhase()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("stop() 은 러닝을 내리고 상향 세션을 NORMAL 로 닫는다")
    void stopClosesUpstreamSession() throws Exception {
        // Given
        WebSocketSession session = connectedSession("up-1");

        // When
        client.stop();

        // Then
        assertThat(client.isRunning()).isFalse();
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("세션 close() 가 실패해도 stop() 은 예외를 전파하지 않는다")
    void stopSwallowsCloseFailure() throws Exception {
        // Given
        WebSocketSession session = connectedSession("up-1");
        willThrow(new IOException("already closed")).given(session).close(any());

        // When & Then
        assertThatCode(() -> client.stop()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("연결된 세션이 없으면 stop() 은 아무것도 닫지 않는다")
    void stopWithoutSessionIsNoop() {
        // Given
        client.start();

        // When & Then
        assertThatCode(() -> client.stop()).doesNotThrowAnyException();
        assertThat(client.isRunning()).isFalse();
    }

    // ── 연결 (degrade / 실패) ────────────────────────────────────────────────

    @Test
    @DisplayName("자격증명 미설정이면 접속을 건너뛰고 disabled 상태를 브로드캐스트한다")
    void connectSkippedWhenCredentialsUnusable() {
        // Given
        given(approvalKeyProvider.quoteCredentials())
                .willReturn(new ConnectionCredentials(null, null, null));
        client.start();

        // When
        client.register(samsungTick);

        // Then
        verify(subscriptionManager).broadcastStatus("disabled",
                "실시간 시세가 연동되지 않았습니다 (실전 KIS 키 필요)");
        verify(approvalKeyProvider, never()).getApprovalKey(any());
    }

    @Test
    @DisplayName("approval_key 발급 실패면 접속을 건너뛰고 disabled 상태를 브로드캐스트한다")
    void connectSkippedWhenApprovalKeyUnavailable() {
        // Given
        given(approvalKeyProvider.quoteCredentials()).willReturn(usableCreds);
        given(approvalKeyProvider.getApprovalKey(usableCreds)).willReturn(null);
        client.start();

        // When
        client.register(samsungTick);

        // Then
        verify(subscriptionManager).broadcastStatus("disabled",
                "실시간 시세 접속키 발급에 실패했습니다");
    }

    @Test
    @DisplayName("러닝 상태가 아니면 접속을 시도하지 않는다")
    void connectSkippedWhenNotRunning() {
        // Given: start() 호출 없음 → running=false

        // When
        client.register(samsungTick);

        // Then
        verify(approvalKeyProvider, never()).getApprovalKey(any());
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    @Test
    @DisplayName("접속 실패 시 활성 구독이 있으면 reconnecting 상태와 함께 재연결을 예약한다")
    void connectFailureSchedulesReconnect() {
        // Given
        givenApprovalKeyAvailable();
        given(subscriptionManager.activeSubKeys()).willReturn(List.of(samsungTick));
        client.start();

        // When: ws-url 이 비 ws 스킴이라 소켓을 열기 전에 실패
        client.register(samsungTick);

        // Then
        verify(subscriptionManager).broadcastStatus("reconnecting", null);
    }

    @Test
    @DisplayName("이미 열린 세션이 있으면 재접속하지 않는다")
    void ensureConnectedReusesOpenSession() {
        // Given
        givenApprovalKeyAvailable();
        connectedSession("up-1");

        // When
        client.register(samsungTick);

        // Then: 접속 시도(=실패 시 reconnecting)가 없어야 한다
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    // ── 구독 프레임 송신 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("register 는 tr_type=1 구독 프레임을 상향으로 보낸다")
    void registerSendsSubscribeFrame() throws Exception {
        // Given
        givenApprovalKeyAvailable();
        WebSocketSession session = connectedSession("up-1");

        // When
        client.register(samsungTick);

        // Then
        String payload = captureSentPayload(session);
        assertThat(payload).contains("\"approval_key\":\"APPROVAL-KEY\"")
                .contains("\"custtype\":\"P\"")
                .contains("\"tr_type\":\"1\"")
                .contains("\"content-type\":\"utf-8\"")
                .contains("\"tr_id\":\"H0STCNT0\"")
                .contains("\"tr_key\":\"005930\"");
    }

    @Test
    @DisplayName("unregister 는 tr_type=2 해제 프레임을 보낸다")
    void unregisterSendsUnsubscribeFrame() throws Exception {
        // Given
        givenApprovalKeyAvailable();
        WebSocketSession session = connectedSession("up-1");

        // When
        client.unregister(appleTick);

        // Then
        String payload = captureSentPayload(session);
        assertThat(payload).contains("\"tr_type\":\"2\"")
                .contains("\"tr_id\":\"HDFSCNT0\"")
                .contains("\"tr_key\":\"DNASAAPL\"");
    }

    @Test
    @DisplayName("registerAll 은 키 개수만큼 등록 프레임을 보낸다")
    void registerAllSendsFramePerKey() throws Exception {
        // Given
        givenApprovalKeyAvailable();
        WebSocketSession session = connectedSession("up-1");

        // When
        client.registerAll(List.of(samsungTick, appleTick));

        // Then
        verify(session, times(2)).sendMessage(any());
    }

    @Test
    @DisplayName("상향 미연결이면 구독 프레임 송신을 건너뛴다 (defer)")
    void subscriptionFrameDeferredWhenNotConnected() {
        // Given: 세션 없음, running=false 라 connect 도 시도하지 않음

        // When
        client.unregister(samsungTick);

        // Then
        verify(approvalKeyProvider, never()).getApprovalKey(any());
    }

    @Test
    @DisplayName("approval_key 가 null 이면 구독 프레임을 보내지 않는다")
    void subscriptionFrameSkippedWhenApprovalKeyNull() throws Exception {
        // Given
        WebSocketSession session = connectedSession("up-1");
        given(approvalKeyProvider.quoteCredentials()).willReturn(usableCreds);
        given(approvalKeyProvider.getApprovalKey(usableCreds)).willReturn(null);

        // When
        client.unregister(samsungTick);

        // Then
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("구독 프레임 송신 실패는 삼켜진다")
    void subscriptionFrameSendFailureIsSwallowed() throws Exception {
        // Given
        givenApprovalKeyAvailable();
        WebSocketSession session = connectedSession("up-1");
        willThrow(new IOException("broken pipe")).given(session).sendMessage(any());

        // When & Then
        assertThatCode(() -> client.unregister(samsungTick)).doesNotThrowAnyException();
    }

    // ── 핸들러 콜백 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("(재)연결 시 활성 SubKey 를 전부 재등록한다")
    void reconnectReRegistersActiveSubKeys() throws Exception {
        // Given
        givenApprovalKeyAvailable();
        given(subscriptionManager.activeSubKeys()).willReturn(List.of(samsungTick, appleTick));
        WebSocketSession session = openSession("up-1");
        client.start();

        // When
        client.afterConnectionEstablished(session);

        // Then
        verify(session, times(2)).sendMessage(any());
        verify(subscriptionManager, never()).broadcastStatus(eq("reconnected"), any());
    }

    @Test
    @DisplayName("재연결 후 접속이 복구되면 reconnected 상태를 브로드캐스트한다")
    void reconnectedStatusBroadcastAfterRecovery() {
        // Given
        givenApprovalKeyAvailable();
        given(subscriptionManager.activeSubKeys()).willReturn(List.of(samsungTick));
        client.start();
        client.afterConnectionClosed(openSession("up-1"), CloseStatus.SERVER_ERROR);
        verify(subscriptionManager, atLeastOnce()).broadcastStatus("reconnecting", null);

        // When
        client.afterConnectionEstablished(openSession("up-2"));

        // Then
        verify(subscriptionManager).broadcastStatus("reconnected", null);
    }

    @Test
    @DisplayName("러닝 중 연결이 끊기면 재연결을 예약한다")
    void connectionClosedWhileRunningSchedulesReconnect() {
        // Given
        given(subscriptionManager.activeSubKeys()).willReturn(List.of(samsungTick));
        WebSocketSession session = connectedSession("up-1");

        // When
        client.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

        // Then
        verify(subscriptionManager).broadcastStatus("reconnecting", null);
    }

    @Test
    @DisplayName("활성 구독이 없으면 연결이 끊겨도 재연결하지 않는다")
    void connectionClosedWithoutActiveSubscriptionsSkipsReconnect() {
        // Given
        WebSocketSession session = connectedSession("up-1");

        // When
        client.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Then
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    @Test
    @DisplayName("정지 상태에서 연결이 끊기면 재연결하지 않는다")
    void connectionClosedWhileStoppedSkipsReconnect() {
        // Given: start() 없음 → running=false
        WebSocketSession session = openSession("up-1");

        // When
        client.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Then
        verify(subscriptionManager, never()).activeSubKeys();
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    @Test
    @DisplayName("전송 오류는 로깅만 하고 예외를 전파하지 않는다")
    void transportErrorIsLoggedOnly() {
        // Given
        WebSocketSession session = openSession("up-1");

        // When & Then
        assertThatCode(() -> client.handleTransportError(session, new IllegalStateException("boom")))
                .doesNotThrowAnyException();
        verify(subscriptionManager, never()).broadcastStatus(anyString(), any());
    }

    // ── 메시지 수신 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 페이로드는 무시한다")
    void emptyPayloadIgnored() {
        // Given
        WebSocketSession session = openSession("up-1");

        // When
        client.handleTextMessage(session, new TextMessage(""));

        // Then
        verify(frameParser, never()).parse(anyString());
    }

    @Test
    @DisplayName("PINGPONG 프레임은 받은 그대로 echo 한다 (keepalive)")
    void pingpongFrameIsEchoed() throws Exception {
        // Given
        WebSocketSession session = openSession("up-1");
        String payload = "{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"20260808120000\"}}";

        // When
        client.handleTextMessage(session, new TextMessage(payload));

        // Then
        assertThat(captureSentPayload(session)).isEqualTo(payload);
        verify(frameParser, never()).parse(anyString());
    }

    @Test
    @DisplayName("구독 ACK JSON 은 로깅만 하고 echo 하지 않는다")
    void subscriptionAckIsNotEchoed() throws Exception {
        // Given
        WebSocketSession session = openSession("up-1");
        String payload = "{\"header\":{\"tr_id\":\"H0STCNT0\"},"
                + "\"body\":{\"rt_cd\":\"0\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}";

        // When
        client.handleTextMessage(session, new TextMessage(payload));

        // Then
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("header/body 가 없는 JSON 프레임도 안전하게 처리한다")
    void jsonFrameWithoutHeaderOrBodyIsSafe() throws Exception {
        // Given
        WebSocketSession session = openSession("up-1");

        // When & Then
        assertThatCode(() -> client.handleTextMessage(session, new TextMessage("{}")))
                .doesNotThrowAnyException();
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("깨진 JSON 프레임은 예외를 전파하지 않는다")
    void malformedJsonFrameIsSwallowed() throws Exception {
        // Given
        WebSocketSession session = openSession("up-1");

        // When & Then
        assertThatCode(() -> client.handleTextMessage(session, new TextMessage("{not-json")))
                .doesNotThrowAnyException();
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("체결 데이터 프레임은 tick 으로 fan-out 한다")
    void tickFrameIsFannedOut() {
        // Given
        WebSocketSession session = openSession("up-1");
        String raw = "0|H0STCNT0|001|005930^090000^70100";
        TickMessage tick = TickMessage.builder().market("KR").symbol("005930").build();
        given(frameParser.parse(raw))
                .willReturn(new KisFrameParser.ParseResult("H0STCNT0", "005930", null, tick));

        // When
        client.handleTextMessage(session, new TextMessage(raw));

        // Then
        verify(subscriptionManager).fanOut(RealtimeTr.KR_TICK, "005930", tick);
    }

    @Test
    @DisplayName("호가 데이터 프레임은 quote 로 fan-out 한다")
    void quoteFrameIsFannedOut() {
        // Given
        WebSocketSession session = openSession("up-1");
        String raw = "0|H0STASP0|001|005930^090000^0";
        QuoteMessage quote = QuoteMessage.builder().market("KR").symbol("005930").build();
        given(frameParser.parse(raw))
                .willReturn(new KisFrameParser.ParseResult("H0STASP0", "005930", quote, null));

        // When
        client.handleTextMessage(session, new TextMessage(raw));

        // Then
        verify(subscriptionManager).fanOut(RealtimeTr.KR_ASKING, "005930", quote);
    }

    @Test
    @DisplayName("파서가 null 을 반환하면 fan-out 하지 않는다")
    void unparsableFrameIsIgnored() {
        // Given
        WebSocketSession session = openSession("up-1");
        String raw = "1|H0STCNI0|001|encrypted";
        given(frameParser.parse(raw)).willReturn(null);

        // When
        client.handleTextMessage(session, new TextMessage(raw));

        // Then
        verify(subscriptionManager, never()).fanOut(any(), anyString(), any());
    }

    @Test
    @DisplayName("quote/tick 이 모두 비어 있으면 fan-out 하지 않는다")
    void emptyParseResultIsIgnored() {
        // Given
        WebSocketSession session = openSession("up-1");
        String raw = "0|H0STCNT0|001|005930";
        given(frameParser.parse(raw))
                .willReturn(new KisFrameParser.ParseResult("H0STCNT0", "005930", null, null));

        // When
        client.handleTextMessage(session, new TextMessage(raw));

        // Then
        verify(subscriptionManager, never()).fanOut(any(), anyString(), any());
    }

    @Test
    @DisplayName("파싱 중 예외가 나도 전파하지 않는다")
    void parseExceptionIsSwallowed() {
        // Given
        WebSocketSession session = openSession("up-1");
        String raw = "0|H0STCNT0|001|broken";
        given(frameParser.parse(raw)).willThrow(new IllegalStateException("parse boom"));

        // When & Then
        assertThatCode(() -> client.handleTextMessage(session, new TextMessage(raw)))
                .doesNotThrowAnyException();
        verify(subscriptionManager, never()).fanOut(any(), anyString(), any());
    }

    // ── 종료 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() 은 러닝을 내리고 세션과 스케줄러를 정리한다")
    void shutdownClosesEverything() throws Exception {
        // Given
        WebSocketSession session = connectedSession("up-1");

        // When
        client.shutdown();

        // Then
        assertThat(client.isRunning()).isFalse();
        verify(session).close(CloseStatus.NORMAL);
    }
}
