package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.market.StockNewsResponse;
import com.inbeom.apiserver.service.StockNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 종목 뉴스 읽기 전용 엔드포인트.
 *
 * <p>ai-agent 가 stock_news 에 적재한 종목 뉴스를 프론트가 목록/상세로 조회한다.
 * 모두 항상 200 + ApiResponse.success 로 응답하며, 데이터가 없으면 빈 리스트/null 로 degrade 한다.
 */
@Slf4j
@RestController
@RequestMapping("/news")
@RequiredArgsConstructor
public class StockNewsController {

    private static final int DEFAULT_RECENT_LIMIT = 20;

    private final StockNewsService stockNewsService;

    /**
     * 뉴스 목록 조회.
     * - symbol 지정: 해당 종목 뉴스 (date 미지정 시 종목 최신 분석일로 fallback)
     * - symbol 미지정: 전체 종목 통합 최신 뉴스 20건
     * GET /api/news?symbol=005930&date=2025-05-27
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockNewsResponse>>> getNews(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/news - symbol: {}, date: {}", symbol, date);

        List<StockNewsResponse> response = (symbol != null && !symbol.isBlank())
                ? stockNewsService.getBySymbol(symbol, date)
                : stockNewsService.getRecent(DEFAULT_RECENT_LIMIT);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 단일 뉴스 상세 조회. 없으면 data=null (200).
     * GET /api/news/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StockNewsResponse>> getNewsById(@PathVariable long id) {
        log.info("GET /api/news/{}", id);

        StockNewsResponse response = stockNewsService.getById(id);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
