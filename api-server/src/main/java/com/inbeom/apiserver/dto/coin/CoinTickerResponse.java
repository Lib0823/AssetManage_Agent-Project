package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 코인 현재가.
 *
 * <p><b>등락 표시에는 반드시 {@code signedChangePrice}/{@code signedChangeRate} 를 쓴다.</b>
 * 업비트 ticker 의 {@code change_price}/{@code change_rate} 는 <b>절대값</b>이라 그대로 그리면
 * 하락도 상승처럼 보인다. 방향은 {@code change}({@code RISE}/{@code EVEN}/{@code FALL}) 에 있다.
 *
 * <p>(같은 이름의 필드가 캔들 응답에서는 부호를 갖는다 — {@link CoinCandleResponse} 와 DTO 를
 * 공유하지 않는 이유다.)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinTickerResponse {

    private String market;

    /** 현재가. 실패 시 null 이며 {@code notice} 가 채워진다. */
    private BigDecimal tradePrice;

    private BigDecimal openingPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal prevClosingPrice;

    /** {@code RISE} / {@code EVEN} / {@code FALL}. */
    private String change;

    /** 부호 있는 전일 대비. UI 는 이 값을 쓴다. */
    private BigDecimal signedChangePrice;

    /** 부호 있는 등락률(0.0124 = 1.24%). UI 는 이 값을 쓴다. */
    private BigDecimal signedChangeRate;

    private BigDecimal accTradePrice24h;
    private BigDecimal accTradeVolume24h;

    private BigDecimal highest52WeekPrice;
    private BigDecimal lowest52WeekPrice;

    /** epoch millis. */
    private Long timestamp;

    /** 정상일 때 null. 실패 시 안내 문구. */
    private String notice;
}
