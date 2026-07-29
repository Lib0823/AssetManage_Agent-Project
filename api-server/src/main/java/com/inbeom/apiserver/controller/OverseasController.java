package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.overseas.OverseasBalanceResponse;
import com.inbeom.apiserver.dto.overseas.OverseasOrderRequest;
import com.inbeom.apiserver.dto.overseas.OverseasOrderableResponse;
import com.inbeom.apiserver.dto.overseas.OverseasOrderbookResponse;
import com.inbeom.apiserver.dto.overseas.OverseasPendingOrderResponse;
import com.inbeom.apiserver.dto.overseas.OverseasPriceResponse;
import com.inbeom.apiserver.dto.overseas.OverseasTradeHistoryResponse;
import com.inbeom.apiserver.service.OverseasQuoteService;
import com.inbeom.apiserver.service.OverseasTradingService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 해외주식(미국) 현재가/잔고/매수/매도 REST API.
 *
 * <p>현재가({@code /overseas/stocks/**})는 공개(permitAll)이며 실전 quote 자격증명으로 조회한다.
 * 잔고/매수/매도는 JWT 인증 후 사용자 mock 키로 처리한다. 모든 경로는 graceful degrade 하므로
 * 미연동/실패 시에도 200 으로 notice 를 담아 응답한다(주문은 {@code data.success=false}).
 */
@Slf4j
@RestController
@RequestMapping("/overseas")
@RequiredArgsConstructor
public class OverseasController {

    private final OverseasQuoteService overseasQuoteService;
    private final OverseasTradingService overseasTradingService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * GET /api/overseas/stocks/{symbol}/price?exchange=NASD
     * 해외주식 현재가상세 (공개). 미연동/실패 시 가격 null + notice.
     */
    @GetMapping("/stocks/{symbol}/price")
    public ResponseEntity<ApiResponse<OverseasPriceResponse>> getPrice(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "exchange", required = false) String exchange
    ) {
        OverseasPriceResponse price = overseasQuoteService.getPrice(symbol, exchange);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas price retrieved", price)
        );
    }

    /**
     * GET /api/overseas/balance
     * 해외주식 잔고 (JWT). 미지원/실패 시 빈 목록 + notice.
     */
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<OverseasBalanceResponse>> getBalance(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        OverseasBalanceResponse balance = overseasTradingService.getBalance(userId);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas balance retrieved", balance)
        );
    }

    /**
     * POST /api/overseas/buy
     * 해외주식 매수 (JWT, 지정가 전용). 실패 시 data.success=false + notice.
     */
    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buy(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody OverseasOrderRequest request
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        Map<String, Object> result = overseasTradingService.buy(userId, request);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas buy order processed", result)
        );
    }

    /**
     * POST /api/overseas/sell
     * 해외주식 매도 (JWT, 지정가 전용). 실패 시 data.success=false + notice.
     */
    @PostMapping("/sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sell(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody OverseasOrderRequest request
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        Map<String, Object> result = overseasTradingService.sell(userId, request);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas sell order processed", result)
        );
    }

    /**
     * GET /api/overseas/stocks/{symbol}/orderbook?exchange=NASD
     * 해외주식 1호가 (공개). 미연동/실패 시 빈 호가 + notice.
     */
    @GetMapping("/stocks/{symbol}/orderbook")
    public ResponseEntity<ApiResponse<OverseasOrderbookResponse>> getOrderbook(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "exchange", required = false) String exchange
    ) {
        OverseasOrderbookResponse orderbook = overseasQuoteService.getOverseasOrderbook(symbol, exchange);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas orderbook retrieved", orderbook)
        );
    }

    /**
     * GET /api/overseas/history?exchange=NASD
     * 해외주식 주문체결내역 (JWT). 미지원/실패 시 빈 목록 + notice.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<OverseasTradeHistoryResponse>> getHistory(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(value = "exchange", required = false) String exchange
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        OverseasTradeHistoryResponse history = overseasTradingService.getHistory(userId, exchange);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas trade history retrieved", history)
        );
    }

    /**
     * GET /api/overseas/pending-orders?exchange=NASD
     * 해외주식 미체결내역 (JWT). 미지원/실패 시 빈 목록 + notice.
     */
    @GetMapping("/pending-orders")
    public ResponseEntity<ApiResponse<OverseasPendingOrderResponse>> getPendingOrders(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(value = "exchange", required = false) String exchange
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        OverseasPendingOrderResponse pending = overseasTradingService.getPendingOrders(userId, exchange);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas pending orders retrieved", pending)
        );
    }

    /**
     * GET /api/overseas/orderable?symbol=AAPL&exchange=NASD&price=180.0
     * 해외주식 매수가능금액 (JWT). 미지원/실패 시 0 + notice.
     */
    @GetMapping("/orderable")
    public ResponseEntity<ApiResponse<OverseasOrderableResponse>> getOrderable(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "exchange", required = false) String exchange,
            @RequestParam(value = "price", required = false) BigDecimal price
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        OverseasOrderableResponse orderable = overseasTradingService.getOrderable(userId, symbol, exchange, price);
        return ResponseEntity.ok(
                ApiResponse.success("Overseas orderable retrieved", orderable)
        );
    }
}
