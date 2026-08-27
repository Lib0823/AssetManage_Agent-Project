package com.inbeom.apiserver.dto.company;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 종목 재무제표 응답 (종목 상세 화면 - 재무제표 탭)
 * GET /company/{stockCode}/financials
 *
 * KIS 국내주식 손익계산서(FHKST66430200) / 재무비율(FHKST66430300) /
 * 안정성비율(FHKST66430600) + 주식현재가 시세(per/pbr) 조합.
 * KIS finance API 미제공/권한없음 시 annual=[], ratios 일부 null degrade.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialsResponse {

    @JsonProperty("stock_code")
    private String stockCode;

    // 연간 손익 (최신순, 최대 3~4개년). KIS finance 미제공 시 빈 리스트.
    @JsonProperty("annual")
    private List<AnnualFinancial> annual;

    @JsonProperty("ratios")
    private Ratios ratios;

    // KIS 시세/재무 미연동 시 UI 안내용 메시지 (정상이면 null)
    @JsonProperty("notice")
    private String notice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnnualFinancial {
        // 연도 (예: "2024", stac_yymm 의 앞 4자리)
        @JsonProperty("year")
        private String year;

        // 매출액 (억원)
        @JsonProperty("revenue")
        private Long revenue;

        // 영업이익 (억원)
        @JsonProperty("operating_profit")
        private Long operatingProfit;

        // 당기순이익 (억원)
        @JsonProperty("net_profit")
        private Long netProfit;

        @JsonProperty("eps")
        private BigDecimal eps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ratios {
        // ROE (%) - 재무비율 API
        @JsonProperty("roe")
        private BigDecimal roe;

        // ROA (%) - KIS 미제공이면 null
        @JsonProperty("roa")
        private BigDecimal roa;

        // PER / PBR - 현재가 시세 API
        @JsonProperty("per")
        private BigDecimal per;

        @JsonProperty("pbr")
        private BigDecimal pbr;

        // 부채비율 (%) - 안정성비율 API
        @JsonProperty("debt_ratio")
        private BigDecimal debtRatio;

        // 유동비율 (%) - 안정성비율 API
        @JsonProperty("current_ratio")
        private BigDecimal currentRatio;
    }
}
