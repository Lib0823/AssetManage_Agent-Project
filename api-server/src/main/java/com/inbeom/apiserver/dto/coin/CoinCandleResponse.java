package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 캔들 한 개.
 *
 * <p><b>{@code changePrice}/{@code changeRate} 는 여기서는 부호가 있다.</b> 같은 이름의 ticker
 * 필드는 절대값이다 — 이름이 같고 의미가 달라서 {@link CoinTickerResponse} 와 DTO 를 공유하지 않는다.
 *
 * <p>{@code candleDateTime*} 은 오프셋이 없는 문자열({@code "2026-08-28T00:00:00"})로 그대로
 * 내려보낸다. 차트 축 라벨 용도라 서버가 타임존을 해석해 붙일 이유가 없고, 해석하면 KST/UTC 중
 * 어느 쪽을 썼는지가 응답에서 사라진다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinCandleResponse {

    private String market;

    /** 예: {@code "2026-08-28T00:00:00"} (오프셋 없음). */
    private String candleDateTimeUtc;

    /** 예: {@code "2026-08-28T09:00:00"} (오프셋 없음). 차트 라벨은 보통 이쪽을 쓴다. */
    private String candleDateTimeKst;

    private BigDecimal openingPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;

    /** 종가. */
    private BigDecimal tradePrice;

    private BigDecimal prevClosingPrice;

    /** 부호 있는 전일 대비. */
    private BigDecimal changePrice;

    /** 부호 있는 등락률. */
    private BigDecimal changeRate;

    private BigDecimal candleAccTradePrice;
    private BigDecimal candleAccTradeVolume;

    /** epoch millis. */
    private Long timestamp;
}
