package com.inbeom.apiserver.dto.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 종목별 뉴스 기사 응답 (저장된 stock_news 1건)
 * GET /news?symbol={stockCode}&date=YYYY-MM-DD · GET /news/{id}
 *
 * <p>ai-agent가 적재한 종목 뉴스를 읽기 전용으로 노출한다.
 * JSON 키는 프론트 호환을 위해 snake_case 를 사용한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockNewsResponse {
    @JsonProperty("id")
    private Long id;

    @JsonProperty("stock_code")
    private String stockCode;

    @JsonProperty("stock_name")
    private String stockName;

    @JsonProperty("title")
    private String title;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("url")
    private String url;

    @JsonProperty("source")
    private String source;

    // 기사별 감성 점수 (-1 ~ 1)
    @JsonProperty("sentiment_score")
    private BigDecimal sentimentScore;

    // 감성 라벨 (positive/negative/neutral) — ai-agent가 저장한 값을 그대로 노출
    @JsonProperty("sentiment_label")
    private String sentimentLabel;

    // 태그 (JSON 문자열 배열을 파싱한 결과)
    @JsonProperty("tags")
    private List<String> tags;

    // 기사 발행 시각 (ISO-8601 문자열, 없으면 null)
    @JsonProperty("published_at")
    private String publishedAt;

    // 분석 기준일 (ISO date 문자열, 없으면 null)
    @JsonProperty("analysis_date")
    private String analysisDate;
}
