package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 해외주식(미국) 1호가 응답 DTO. 실전 quote 도메인 + 실전 quote 자격증명 사용.
 *
 * <p>KIS 해외주식 현재가 1호가(HHDFS76200100, /quotations/inquire-asking-price) output 매핑(필드명 MUST-VERIFY):
 * pbid1 → bids[0].price, vbid1 → bids[0].quantity,
 * pask1 → asks[0].price, vask1 → asks[0].quantity.
 * (실전 1호가 응답은 매수/매도 각 1단계만 제공 — 국내 10호가와 달리 단일 레벨.)
 *
 * <p>시세 미연동(quote 키 미설정)·해외시세 권한없음·rt_cd != 0·예외 시 asks/bids 빈 목록 +
 * {@code notice} 에 UI 안내 메시지를 담는다(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasOrderbookResponse {

    private String symbol;        // 종목코드 (예: AAPL)

    private String exchange;      // 잔고/매매 거래소코드 (NASD/NYSE/AMEX)

    private String currency;      // 통화 (기본 USD)

    private List<OrderbookLevel> asks;  // 매도호가 (1단계)

    private List<OrderbookLevel> bids;  // 매수호가 (1단계)

    private String notice;        // 미연동/실패 시 UI 안내용 메시지 (정상이면 null)

    /**
     * 호가 단건 (가격 + 잔량).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderbookLevel {

        private BigDecimal price;     // 호가 가격

        private Integer quantity;     // 호가 잔량
    }
}
