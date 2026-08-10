package com.inbeom.apiserver.dto.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/**
 * {@code trade.order.result} 메시지 (api-server 발행 → ai-agent 소비).
 *
 * <p>주문 1건당 정확히 1번 발행하는 것을 목표로 한다. {@code status} 는
 * {@link #STATUS_SUCCESS} / {@link #STATUS_FAILED} 둘 중 하나다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeOrderResultMessage(
        String idempotencyKey,
        Long userId,
        String stockCode,
        String side,
        String status,
        String kisOrderNo,
        String errorMessage,
        OffsetDateTime processedAt
) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static TradeOrderResultMessage success(TradeOrderRequestMessage request, String kisOrderNo) {
        return new TradeOrderResultMessage(
                request.idempotencyKey(), request.userId(), request.stockCode(), request.side(),
                STATUS_SUCCESS, kisOrderNo, null, OffsetDateTime.now());
    }

    public static TradeOrderResultMessage failed(TradeOrderRequestMessage request, String errorMessage) {
        return new TradeOrderResultMessage(
                request.idempotencyKey(), request.userId(), request.stockCode(), request.side(),
                STATUS_FAILED, null, errorMessage, OffsetDateTime.now());
    }
}
