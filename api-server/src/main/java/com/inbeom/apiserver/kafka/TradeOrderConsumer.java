package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import com.inbeom.apiserver.service.TradeOrderIdempotencyService;
import com.inbeom.apiserver.service.TradeOrderIdempotencyService.ClaimResult;
import com.inbeom.apiserver.service.TradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code trade.order.requested} 컨슈머 — ai-agent Stage 6 매매 주문을 실제 KIS 주문으로 실행한다.
 *
 * <h2>재시도 경계: "예외 타입"이 아니라 "KIS 호출 여부"</h2>
 * 이 컨슈머의 핵심 설계다. 돈이 걸린 시스템에서 재시도는 <b>중복 주문</b>을 뜻할 수 있으므로,
 * 처리를 두 단계로 명확히 쪼갠다.
 *
 * <table border="1">
 *   <caption>단계별 실패 처리</caption>
 *   <tr><th>단계</th><th>KIS 접촉</th><th>실패 시</th></tr>
 *   <tr>
 *     <td>PHASE 0 — 파싱/계약 검증</td><td>없음</td>
 *     <td>재시도해도 같은 결과 → 즉시 DLQ (예외를 던지지 않음)</td>
 *   </tr>
 *   <tr>
 *     <td>PHASE 1 — 멱등키 선점(DB)</td><td>없음</td>
 *     <td><b>예외를 그대로 던진다</b> → {@code DefaultErrorHandler} 가 지수 백오프 재시도,
 *         소진 시 {@link TradeOrderDlqRecoverer} 가 DLQ 발행. KIS 를 아직 안 건드렸으니 안전하다.</td>
 *   </tr>
 *   <tr>
 *     <td>PHASE 2 — KIS 주문</td><td><b>있음</b></td>
 *     <td><b>절대 재시도하지 않는다</b>. FAILED 기록 + FAILED 결과 + DLQ 발행 후 정상 종료
 *         (예외를 삼켜 Kafka 재전달을 막는다).</td>
 *   </tr>
 * </table>
 *
 * <p><b>왜 예외 타입으로 나누지 않는가</b>: {@code KisApiClient} 는 네트워크 타임아웃
 * ({@code ResourceAccessException})마저 {@code KisApiException}(4003) 으로 감싸서 던진다. 즉
 * "요청이 KIS 에 도달했는지 불확실한 실패"와 "KIS 가 명시적으로 거부한 실패"가 같은 타입으로
 * 올라온다. 타입으로는 구분할 수 없고, 애초에 <b>둘 다 재시도 대상이 아니다</b>:
 * <ul>
 *   <li>(a) 타임아웃/커넥션 리셋 — 이미 체결됐을 수 있어 재시도하면 중복 주문 위험</li>
 *   <li>(b) 잔고부족/종목코드 오류 등 KIS 의 명시적 거부 — 재시도해도 결과가 같음</li>
 * </ul>
 * 그래서 "PHASE 2 에서 난 예외는 전부 재시도 금지"라는 <b>위치 기반</b> 규칙이
 * 타입 기반 분기보다 안전하고 단순하다. (a) 는 DLQ 에서 사람이 KIS 실제 체결내역과 대조한다.
 *
 * <p><b>예외 하나: {@link KisRateLimitExceededException}</b>. 자체 토큰 버킷이 KIS 로 요청을
 * 보내기 <b>전에</b> 거부한 경우이므로 위치 기반 규칙의 전제("PHASE 2 = KIS 접촉")가 성립하지
 * 않는다. 소켓조차 열리지 않았음이 보장되므로 선점을 되돌리고 PHASE 1 처럼 재시도한다 —
 * 그렇지 않으면 잠깐의 호출 폭주 때문에 유효한 주문이 즉시 DLQ 로 유실된다.
 *
 * <p>기존 REST 경로({@code /api/internal/users/{userId}/trades/{buy|sell}})는 수동/디버깅용으로
 * 그대로 남아 있으며 deprecated 표시만 되어 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderConsumer {

    private final TradeOrderIdempotencyService idempotencyService;
    private final TradingService tradingService;
    private final TradeOrderMessagePublisher publisher;
    private final TradeOrderJsonCodec codec;

    @KafkaListener(
            topics = TradeOrderTopics.REQUESTED,
            groupId = "${kafka.trade-order.group-id:api-server-trade-order}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTradeOrderRequested(ConsumerRecord<String, String> record) {
        // ---------- PHASE 0: 파싱 · 계약 검증 (KIS 미접촉, 재시도 무의미) ----------
        TradeOrderRequestMessage request;
        try {
            request = codec.readRequest(record.value());
        } catch (Exception e) {
            log.error("Malformed trade order message at offset={}: {}", record.offset(), record.value(), e);
            publisher.publishDlq(com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage.unparseable(
                    "역직렬화 실패: " + e.getMessage() + " / 원문: " + record.value()));
            return;
        }

        String violation = request.validationError();
        if (violation != null) {
            log.error("Invalid trade order message: key={}, violation={}", request.idempotencyKey(), violation);
            publisher.publishResult(TradeOrderResultMessage.failed(request, "계약 위반: " + violation));
            publisher.publishDlq(request, "계약 위반: " + violation, 0);
            return;
        }

        // ---------- PHASE 1: 멱등키 선점 (KIS 미접촉, 실패 시 재시도 안전) ----------
        // 여기서 발생하는 DB/인프라 예외는 의도적으로 밖으로 던진다 → DefaultErrorHandler 재시도.
        ClaimResult claim;
        try {
            claim = idempotencyService.claim(request);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반 = 다른 컨슈머(또는 재전달된 같은 메시지)가 방금 선점했다.
            // 인프라 오류가 아니라 "이미 처리 중"이라는 확정 신호이므로 재시도하지 않는다.
            log.info("Idempotent skip (unique violation): key={}", request.idempotencyKey());
            return;
        }

        if (claim.duplicate()) {
            if (claim.isUnresolvedPending()) {
                // 이전 시도가 PENDING 인 채로 끊겼다 = KIS 도달 여부 불확실.
                // 다시 주문하면 중복 주문 위험이므로 실행하지 않고 사람에게 넘긴다.
                String reason = "이전 시도가 PENDING 상태로 남아 있어 KIS 도달 여부가 불확실 "
                        + "(tradeHistoryId=" + claim.tradeHistoryId() + "). KIS 체결내역 대조 필요.";
                log.error("Unresolved PENDING trade order -> DLQ. key={}", request.idempotencyKey());
                publisher.publishDlq(request, reason, 0);
            } else {
                log.info("Skip already-processed trade order: key={}, status={}",
                        request.idempotencyKey(), claim.existingStatus());
            }
            return;
        }

        // ---------- PHASE 2: KIS 주문 (여기서부터 재시도 금지) ----------
        executeAgainstKis(request, claim);
    }

    /**
     * KIS 주문 실행. <b>어떤 예외도 밖으로 던지지 않는다</b> — 던지면 Kafka 가 재전달하고,
     * 그것이 곧 중복 주문 위험이기 때문이다.
     */
    private void executeAgainstKis(TradeOrderRequestMessage request, ClaimResult claim) {
        try {
            Map<String, Object> kisResponse = request.isBuy()
                    ? tradingService.executeBuy(
                            request.userId(), claim.kisAccountId(), request.stockCode(),
                            claim.stockName(), request.quantity(), request.priceOrZero())
                    : tradingService.executeSell(
                            request.userId(), claim.kisAccountId(), request.stockCode(),
                            claim.stockName(), request.quantity(), request.priceOrZero());

            String kisOrderNo = extractOrderNumber(kisResponse);
            idempotencyService.markAccepted(claim.tradeHistoryId(), kisOrderNo);
            publisher.publishResult(TradeOrderResultMessage.success(request, kisOrderNo));
            log.info("Trade order executed: key={}, kisOrderNo={}", request.idempotencyKey(), kisOrderNo);

        } catch (KisRateLimitExceededException e) {
            // PHASE 2 안에서 났지만 PHASE 1 로 되돌리는 유일한 예외다.
            // 자체 토큰 버킷이 KIS 로 요청을 보내기 전에 거부한 것이므로 "KIS 미접촉"이 보장된다
            // — 이 위치 기반 규칙의 전제(=여기 오면 KIS 를 건드렸다)가 성립하지 않는 유일한 경우.
            // 이걸 확정 실패로 처리하면 잠깐의 호출 폭주 때문에 유효한 주문이 즉시 DLQ 로 유실된다.
            //
            // 선점 행을 반드시 먼저 지운다: 남겨두면 재전달 시 claim() 이 PENDING 을 발견해
            // "도달 여부 불확실"로 DLQ 를 태우고, 재시도가 무의미해진다.
            log.warn("Trade order rate-limited before reaching KIS (will retry): key={}",
                    request.idempotencyKey());
            idempotencyService.release(claim.tradeHistoryId());
            throw e;

        } catch (Exception e) {
            // (a) 타임아웃/커넥션 리셋 → KIS 도달 불확실, (b) KIS 명시적 거부 → 재시도해도 동일.
            // 둘 다 재시도하지 않고 확정 실패로 남긴 뒤 DLQ 로 넘겨 사람이 대조하게 한다.
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Trade order failed at KIS phase (NOT retried): key={}", request.idempotencyKey(), e);

            safelyMarkFailed(claim.tradeHistoryId());
            publisher.publishResult(TradeOrderResultMessage.failed(request, reason));
            publisher.publishDlq(request, "KIS 호출 단계 실패 (재시도 안 함, 실제 체결 여부 대조 필요): " + reason, 0);
        }
    }

    /**
     * FAILED 기록마저 실패해도 예외를 밖으로 내보내지 않는다. PENDING 인 채로 남지만,
     * 그 상태 자체가 "사람이 확인해야 한다"는 신호이고 DLQ 메시지도 이미 발행된다.
     */
    private void safelyMarkFailed(Long tradeHistoryId) {
        try {
            idempotencyService.markFailed(tradeHistoryId);
        } catch (Exception e) {
            log.error("Failed to mark trade_history FAILED (id={}); row stays PENDING.", tradeHistoryId, e);
        }
    }

    /** KIS 주문 응답에서 주문번호(ODNO) 추출. */
    @SuppressWarnings("unchecked")
    private String extractOrderNumber(Map<String, Object> kisResponse) {
        if (kisResponse == null) {
            return null;
        }
        Object output = kisResponse.get("output");
        if (output instanceof Map<?, ?> map) {
            Object odno = ((Map<String, Object>) map).get("ODNO");
            return odno != null ? String.valueOf(odno) : null;
        }
        return null;
    }
}
