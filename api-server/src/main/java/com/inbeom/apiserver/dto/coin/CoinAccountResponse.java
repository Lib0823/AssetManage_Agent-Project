package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 업비트 보유 자산 한 줄 ({@code GET /v1/accounts}).
 *
 * <p>원화(KRW) 잔고도 한 줄로 온다 — 그때 {@code market} 은 null 이다(원화는 마켓이 아니다).
 *
 * <p><b>평가금액은 여기 없다.</b> 업비트는 보유 수량만 주므로 원화 환산은 <b>수량 × 현재가</b>로
 * 계산해야 하는데, 그 현재가는 보유 종목마다 티커를 부르는 대신 {@code /coins/tickers} 로
 * <b>1회 배치 조회</b>해야 한다. 종목별 루프는 IP 단위 10 req/s 한도를 즉시 소진해 다른 사용자의
 * 시세까지 막는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinAccountResponse {

    /** 화폐/코인 심볼 (예: {@code BTC}, {@code KRW}). */
    private String currency;

    /** 마켓 코드 (예: {@code KRW-BTC}). 원화 잔고 행에서는 null. */
    private String market;

    /** 주문 가능 수량. */
    private BigDecimal balance;

    /** 주문에 묶여 있는 수량. */
    private BigDecimal locked;

    /** 매수 평균가. 원화 잔고 행에서는 의미가 없다. */
    private BigDecimal avgBuyPrice;

    /** 평단가 기준 화폐 (원화 마켓만 다루므로 항상 {@code KRW}). */
    private String unitCurrency;
}
