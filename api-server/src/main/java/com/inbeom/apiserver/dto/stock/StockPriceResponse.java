package com.inbeom.apiserver.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 종목 현재가 응답 (검색 화면 - SearchView).
 * GET /stocks/{stockCode}/price
 *
 * KIS 주식현재가 시세(FHKST01010100) 매핑:
 * stck_prpr→currentPrice, prdy_vrss→changeAmount, prdy_ctrt→changeRate.
 * 시세 미연동(quote 비활성) 또는 조회 실패 시 가격 필드는 null, notice 에 안내 메시지.
 * JSON 직렬화는 camelCase — 프론트가 camelCase로 읽는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceResponse {

    private String stockCode;

    // 현재가 (원)
    private Long currentPrice;

    // 전일 대비 (원)
    private Long changeAmount;

    // 전일 대비 등락률 (%)
    private BigDecimal changeRate;

    // 시세 미연동 시 UI 안내용 메시지 (정상이면 null)
    private String notice;
}
