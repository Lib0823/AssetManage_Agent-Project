package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.dto.trade.OrderableResponse;
import com.inbeom.apiserver.dto.trade.PendingOrderResponse;
import com.inbeom.apiserver.dto.trade.PlaceReservedOrderRequest;
import com.inbeom.apiserver.dto.trade.RecentTradeResponse;
import com.inbeom.apiserver.dto.trade.ReservedOrderResponse;
import com.inbeom.apiserver.dto.trade.ReservedOrderResultResponse;
import com.inbeom.apiserver.dto.trade.TradeHistoryResponse;
import com.inbeom.apiserver.dto.trade.TradeRequest;
import com.inbeom.apiserver.service.TradingService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/trading")
@RequiredArgsConstructor
public class TradingController {

    private final TradingService tradingService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * POST /api/trading/buy
     * Execute buy order via KIS API
     */
    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buy(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody TradeRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        Map<String, Object> result = tradingService.executeBuy(
                userId,
                kisAccountId,
                request.getStockCode(),
                request.getStockName(),
                request.getQuantity(),
                request.getPrice()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Buy order executed successfully", result)
        );
    }

    /**
     * POST /api/trading/sell
     * Execute sell order via KIS API
     */
    @PostMapping("/sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sell(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody TradeRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        Map<String, Object> result = tradingService.executeSell(
                userId,
                kisAccountId,
                request.getStockCode(),
                request.getStockName(),
                request.getQuantity(),
                request.getPrice()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Sell order executed successfully", result)
        );
    }

    /**
     * GET /api/trading/history
     * Get trade history from KIS API (최근 3개월)
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<TradeHistoryResponse>>> getHistory(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<TradeHistoryResponse> history = tradingService.getTradeHistory(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Trade history retrieved from KIS API successfully", history)
        );
    }

    /**
     * GET /api/trading/recent
     * 홈 화면 알림용 최근 거래내역 (DB trade_history 기반). KIS 라이브가 아니므로 빠르고 안정적이다.
     */
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<RecentTradeResponse>>> getRecentTrades(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<RecentTradeResponse> trades = tradingService.getRecentTrades(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Recent trades retrieved successfully", trades)
        );
    }

    /**
     * GET /api/trading/holdings
     * Get current holdings from KIS API (보유 종목 조회)
     */
    @GetMapping("/holdings")
    public ResponseEntity<ApiResponse<BalanceSummaryResponse>> getHoldings(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        BalanceSummaryResponse holdings = tradingService.getHoldings(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Holdings retrieved successfully", holdings)
        );
    }

    /**
     * GET /api/trading/pending-orders
     * 미체결 주문 조회. KIS 주식일별주문체결조회(VTTC0081R) 결과 중 미체결(잔량 > 0, PENDING/PARTIAL)만 반환.
     */
    @GetMapping("/pending-orders")
    public ResponseEntity<ApiResponse<List<PendingOrderResponse>>> getPendingOrders(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<PendingOrderResponse> pendingOrders = tradingService.getPendingOrders(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Pending orders retrieved successfully", pendingOrders)
        );
    }

    /**
     * GET /api/trading/orderable?stockCode={code}&price={price}
     * 매수가능조회 (KIS VTTC8908R). 최대매수수량/주문가능현금 조회.
     * price 미지정 시 0(시장가 기준)으로 조회한다.
     */
    @GetMapping("/orderable")
    public ResponseEntity<ApiResponse<OrderableResponse>> getOrderable(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("stockCode") String stockCode,
            @RequestParam(value = "price", required = false) BigDecimal price
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        OrderableResponse orderable = tradingService.getOrderable(userId, stockCode, price);

        return ResponseEntity.ok(
                ApiResponse.success("Orderable info retrieved successfully", orderable)
        );
    }

    /**
     * POST /api/trading/reserved-orders
     * 국내주식 예약주문 접수 (KIS CTSC0008U, 실전 전용).
     * KIS rt_cd != 0 시에도 예외 없이 success=false 로 graceful 반환한다.
     */
    @PostMapping("/reserved-orders")
    public ResponseEntity<ApiResponse<ReservedOrderResultResponse>> placeReservedOrder(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody PlaceReservedOrderRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        ReservedOrderResultResponse result = tradingService.placeReservedOrder(userId, kisAccountId, request);

        return ResponseEntity.ok(
                ApiResponse.success("Reserved order request processed", result)
        );
    }

    /**
     * GET /api/trading/reserved-orders
     * 국내주식 예약주문 목록 조회 (KIS CTSC0004R, 실전 전용).
     */
    @GetMapping("/reserved-orders")
    public ResponseEntity<ApiResponse<List<ReservedOrderResponse>>> getReservedOrders(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<ReservedOrderResponse> reservedOrders = tradingService.getReservedOrders(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Reserved orders retrieved successfully", reservedOrders)
        );
    }

    /**
     * DELETE /api/trading/reserved-orders/{seq}?orgNo={orgNo}&orderDate={YYYYMMDD}
     * 국내주식 예약주문 취소 (KIS CTSC0009U, 실전 전용).
     * KIS rt_cd != 0 시에도 예외 없이 success=false 로 graceful 반환한다.
     */
    @DeleteMapping("/reserved-orders/{seq}")
    public ResponseEntity<ApiResponse<ReservedOrderResultResponse>> cancelReservedOrder(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("seq") String seq,
            @RequestParam("orgNo") String orgNo,
            @RequestParam("orderDate") String orderDate
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(userId, kisAccountId, seq, orgNo, orderDate);

        return ResponseEntity.ok(
                ApiResponse.success("Reserved order cancel request processed", result)
        );
    }
}
