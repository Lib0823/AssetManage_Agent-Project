package com.inbeom.apiserver.dto.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code trade.order.dlq} 메시지 — 사람이 KIS 실제 체결내역과 대조해야 하는 주문.
 *
 * <p>{@code trade.order.requested} 와 동일한 필드 + {@code failureReason} / {@code retryCount}.
 *
 * <p><b>중요</b>: DLQ 에 있다는 것이 "주문이 나가지 않았다"는 뜻은 아니다. KIS 네트워크
 * 타임아웃으로 들어온 건은 <b>요청이 도달했는지 자체가 불확실</b>하므로, 재주문 전에
 * 반드시 KIS 잔고/체결내역을 먼저 확인해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeOrderDlqMessage(
        String idempotencyKey,
        Long userId,
        String stockCode,
        String side,
        Integer quantity,
        BigDecimal price,
        LocalDate tradeDate,
        OffsetDateTime requestedAt,
        String failureReason,
        int retryCount
) {

    public static TradeOrderDlqMessage from(TradeOrderRequestMessage request, String failureReason, int retryCount) {
        return new TradeOrderDlqMessage(
                request.idempotencyKey(), request.userId(), request.stockCode(), request.side(),
                request.quantity(), request.price(), request.tradeDate(), request.requestedAt(),
                failureReason, retryCount);
    }

    /**
     * 역직렬화조차 실패해 원본 필드를 알 수 없는 경우. 원문은 {@code failureReason} 에 담는다.
     */
    public static TradeOrderDlqMessage unparseable(String failureReason) {
        return new TradeOrderDlqMessage(
                null, null, null, null, null, null, null, null, failureReason, 0);
    }
}
