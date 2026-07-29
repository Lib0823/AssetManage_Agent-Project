package com.inbeom.apiserver.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 종목 검색 결과 항목 (검색 화면 - SearchView).
 * GET /stocks/search?q=
 *
 * stock_master 카탈로그에서 코드 prefix / 종목명 부분일치로 조회한다.
 * JSON 직렬화는 camelCase(stockCode/stockName) — 프론트가 camelCase로 읽는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSearchResponse {

    private String stockCode;

    private String stockName;

    private String market;

    // 해외(US) 거래소 코드 (NASD/NYSE/AMEX). 국내는 null.
    private String exchangeCode;
}
