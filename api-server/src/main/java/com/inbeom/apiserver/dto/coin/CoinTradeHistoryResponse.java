package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 코인 주문 이력 한 줄 (DB {@code coin_trade_history}).
 *
 * <p>업비트 주문 조회 API 가 이 기능의 범위 밖이라 <b>이 기록은 접수 시점의 스냅샷</b>이다.
 * {@code submittedState} 는 이후 갱신되지 않는다 — 화면에도 "접수 상태"로 표기해야 한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinTradeHistoryResponse {

    private Long id;
    private String market;
    private String coinName;

    /** {@code bid}(매수) / {@code ask}(매도). */
    private String orderSide;

    /** {@code limit} / {@code price} / {@code market}. */
    private String ordType;

    /** 접수 상태 (체결 상태 아님). */
    private String submittedState;

    private BigDecimal volume;
    private BigDecimal price;
    private BigDecimal executedVolume;
    private BigDecimal paidFee;

    private String orderUuid;
    private String identifier;

    private OffsetDateTime orderedAt;
}
