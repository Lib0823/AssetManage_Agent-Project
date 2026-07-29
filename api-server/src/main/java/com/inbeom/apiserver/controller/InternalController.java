package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.internal.AutoTradingUserResponse;
import com.inbeom.apiserver.dto.internal.InternalTradeRequest;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.service.InternalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 서비스-투-서비스 내부 엔드포인트 (ai-agent 멀티유저 파이프라인 전용).
 *
 * <p>인증은 {@code com.inbeom.apiserver.security.InternalAuthFilter} 가
 * {@code X-Internal-Api-Key} 헤더로 수행한다(사람 JWT 아님). 사람용 거래 엔드포인트
 * ({@code /trading/**}, JWT)와 분리된 채널이며, userId 를 명시적으로 받는다.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final InternalService internalService;

    /**
     * GET /api/internal/auto-trading/users
     * 자동매매 활성 사용자(+kisAccountId/예산/설정) 목록.
     */
    @GetMapping("/auto-trading/users")
    public ResponseEntity<ApiResponse<List<AutoTradingUserResponse>>> getActiveAutoTradingUsers() {
        log.info("GET /api/internal/auto-trading/users");
        List<AutoTradingUserResponse> users = internalService.getActiveAutoTradingUsers();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    /**
     * GET /api/internal/users/{userId}/holdings
     * 특정 사용자의 보유종목(해당 사용자 KIS 키 기반).
     */
    @GetMapping("/users/{userId}/holdings")
    public ResponseEntity<ApiResponse<BalanceSummaryResponse>> getHoldings(@PathVariable Long userId) {
        log.info("GET /api/internal/users/{}/holdings", userId);
        BalanceSummaryResponse holdings = internalService.getHoldings(userId);
        return ResponseEntity.ok(ApiResponse.success(holdings));
    }

    /**
     * POST /api/internal/users/{userId}/trades/buy
     * 특정 사용자 명의 매수 주문.
     */
    @PostMapping("/users/{userId}/trades/buy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buy(
            @PathVariable Long userId,
            @Valid @RequestBody InternalTradeRequest request
    ) {
        log.info("POST /api/internal/users/{}/trades/buy stockCode={}", userId, request.getStockCode());
        Map<String, Object> result = internalService.executeBuy(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Buy order executed successfully", result));
    }

    /**
     * POST /api/internal/users/{userId}/trades/sell
     * 특정 사용자 명의 매도 주문.
     */
    @PostMapping("/users/{userId}/trades/sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sell(
            @PathVariable Long userId,
            @Valid @RequestBody InternalTradeRequest request
    ) {
        log.info("POST /api/internal/users/{}/trades/sell stockCode={}", userId, request.getStockCode());
        Map<String, Object> result = internalService.executeSell(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Sell order executed successfully", result));
    }
}
