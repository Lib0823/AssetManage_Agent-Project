package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 코인 주문 접수 결과.
 *
 * <p><b>{@code submittedState} 는 체결 상태가 아니다.</b> 업비트 주문 응답의 {@code state} 는
 * 접수 직후 값이라 거의 항상 {@code wait} 이고, 주문 조회 API 가 이 기능의 범위 밖이라 이후
 * 갱신되지 않는다. UI 도 "접수 상태"로 표기해야 한다 — "체결 안 됨"으로 읽은 사용자가 같은 주문을
 * 한 번 더 내는 것이 이 기능에서 가장 비싼 사고다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinOrderResponse {

    /** 업비트 주문 식별자. <b>UUID 문자열</b>이며 KIS 의 숫자 주문번호와 형식이 다르다. */
    private String orderUuid;

    private String market;

    /** 업비트 원문: {@code bid}(매수) / {@code ask}(매도). */
    private String side;

    /** 업비트 원문: {@code limit} / {@code price} / {@code market}. */
    private String ordType;

    /** 접수 상태 (체결 상태 아님). */
    private String submittedState;

    private BigDecimal volume;
    private BigDecimal price;
    private BigDecimal executedVolume;
    private BigDecimal remainingVolume;
    private BigDecimal paidFee;

    /** 전송한 멱등키. */
    private String identifier;

    /** 업비트가 보고한 접수 시각(오프셋 포함). */
    private OffsetDateTime orderedAt;

    /**
     * 같은 멱등키의 주문이 이미 있어 업비트를 호출하지 않고 기존 결과를 돌려준 경우 true.
     * 프론트는 이 경우 "이미 접수된 주문입니다"로 안내하면 된다.
     */
    private boolean duplicate;
}
