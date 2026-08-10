package com.inbeom.apiserver.dto.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code trade.order.requested} 메시지 (ai-agent 발행 → api-server 소비).
 *
 * <p>Kafka 레코드 key 는 {@link #idempotencyKey()} 와 동일한 문자열이며
 * ({@code "{userId}:{stockCode}:{tradeDate}:{side}"}), 같은 키가 항상 같은 파티션으로 가서
 * 순서가 보장되도록 한다.
 *
 * <p>{@code price} 가 0 이면 시장가다 — ai-agent Stage 6 파이프라인 경로는 항상 0 을 보낸다.
 * {@code TradingService} 는 이 경우 매수여력 조회(지정가 기준)를 건너뛰고 곧바로
 * {@code ORD_DVSN="01"}(시장가) 주문을 낸다.
 *
 * <p>알 수 없는 필드는 무시한다 — 반대편(ai-agent)이 필드를 추가해도 소비가 깨지지 않게 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeOrderRequestMessage(
        String idempotencyKey,
        Long userId,
        String stockCode,
        String side,
        Integer quantity,
        BigDecimal price,
        LocalDate tradeDate,
        OffsetDateTime requestedAt
) {

    public static final String SIDE_BUY = "BUY";
    public static final String SIDE_SELL = "SELL";

    public boolean isBuy() {
        return SIDE_BUY.equalsIgnoreCase(side);
    }

    public boolean isSell() {
        return SIDE_SELL.equalsIgnoreCase(side);
    }

    /** 주문 단가. null 이면 0(시장가)으로 취급한다. */
    public BigDecimal priceOrZero() {
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 계약 필수값 검증. 여기서 걸리는 메시지는 몇 번을 다시 소비해도 같은 결과이므로
     * 재시도하지 않고 곧바로 DLQ 로 보낸다.
     *
     * @return 위반 사유. 유효하면 null.
     */
    public String validationError() {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "idempotencyKey is required";
        }
        if (userId == null) {
            return "userId is required";
        }
        if (stockCode == null || stockCode.isBlank()) {
            return "stockCode is required";
        }
        if (!isBuy() && !isSell()) {
            return "side must be BUY or SELL (was: " + side + ")";
        }
        if (quantity == null || quantity < 1) {
            return "quantity must be >= 1 (was: " + quantity + ")";
        }
        return null;
    }
}
