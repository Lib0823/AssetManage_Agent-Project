package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.stock.OrderbookResponse;
import com.inbeom.apiserver.dto.stock.StockPriceResponse;
import com.inbeom.apiserver.dto.stock.StockSearchResponse;
import com.inbeom.apiserver.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 종목 검색·현재가 조회용 컨트롤러 (검색 화면 - SearchView).
 *
 * 단일 사용자(MVP)·읽기 전용이며 인증을 요구하지 않는다 (SecurityConfig 에서 /stocks/** permitAll).
 * 시세 비활성/실패는 서비스 계층에서 null + notice 로 degrade 되며 항상 200 + ApiResponse.success.
 */
@Slf4j
@RestController
@RequestMapping("/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    /**
     * 종목 검색 (코드 prefix / 종목명 부분일치, 최대 30건)
     * GET /api/stocks/search?q=&market=
     *
     * <p>{@code market=US} 면 해외(USD) 종목을, 그 외(미지정 포함)는 국내(KRW) 종목을 검색한다.
     * 기존 파라미터 없는 호출은 국내 결과를 유지한다.
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<StockSearchResponse>>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "market", required = false) String market
    ) {
        log.info("GET /api/stocks/search?q={}&market={}", q, market);
        List<StockSearchResponse> response = stockService.searchStocks(q, market);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 검색 화면 기본 노출용 상위 종목 (국내 코스피 대표, 최대 30건)
     * GET /api/stocks/top?market=
     */
    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<StockSearchResponse>>> top(
            @RequestParam(value = "market", required = false) String market
    ) {
        log.info("GET /api/stocks/top?market={}", market);
        List<StockSearchResponse> response = stockService.topStocks(market);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 종목 현재가 (시세 비활성/실패 시 가격 null + notice)
     * GET /api/stocks/{stockCode}/price
     */
    @GetMapping("/{stockCode}/price")
    public ResponseEntity<ApiResponse<StockPriceResponse>> getPrice(
            @PathVariable String stockCode
    ) {
        log.info("GET /api/stocks/{}/price", stockCode);
        StockPriceResponse response = stockService.getPrice(stockCode);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 종목 호가 (시세 비활성/실패 시 asks/bids 빈 리스트 + notice)
     * GET /api/stocks/{stockCode}/orderbook
     */
    @GetMapping("/{stockCode}/orderbook")
    public ResponseEntity<ApiResponse<OrderbookResponse>> getOrderbook(
            @PathVariable String stockCode
    ) {
        log.info("GET /api/stocks/{}/orderbook", stockCode);
        OrderbookResponse response = stockService.getOrderbook(stockCode);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
