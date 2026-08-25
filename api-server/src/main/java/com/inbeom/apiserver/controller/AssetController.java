package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.asset.AssetHistoryResponse;
import com.inbeom.apiserver.dto.asset.AssetSnapshotRequest;
import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.service.AssetService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * GET /api/assets/holdings
     * Get user's stock holdings from KIS API
     */
    @GetMapping("/holdings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHoldings(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        Map<String, Object> holdings = assetService.getHoldings(kisAccountId);

        return ResponseEntity.ok(
                ApiResponse.success("Holdings retrieved successfully", holdings)
        );
    }

    /**
     * GET /api/assets/balance
     * Get user's cash balance from KIS API
     */
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBalance(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long kisAccountId = jwtTokenProvider.getKisAccountIdFromToken(token);

        Map<String, Object> balance = assetService.getBalance(kisAccountId);

        return ResponseEntity.ok(
                ApiResponse.success("Balance retrieved successfully", balance)
        );
    }

    /**
     * POST /api/assets/snapshot
     * 오늘 총자산 스냅샷 upsert (자산 추이 라인차트용)
     */
    @PostMapping("/snapshot")
    public ResponseEntity<ApiResponse<Void>> recordSnapshot(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody AssetSnapshotRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        assetService.recordSnapshot(userId, request.getTotalAsset());

        return ResponseEntity.ok(
                ApiResponse.success("Asset snapshot recorded successfully", null)
        );
    }

    /**
     * GET /api/assets/history?days=30
     * 자산 추이(일별 총자산 스냅샷) 조회 — 날짜 오름차순.
     * {@code days} 는 1~365 로 클램프된다(범위 밖 값은 거부하지 않고 가장 가까운 경계로 맞춘다).
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<AssetHistoryResponse>>> getHistory(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(name = "days", defaultValue = "30") int days
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<AssetHistoryResponse> history = assetService.getHistory(userId, days);

        return ResponseEntity.ok(
                ApiResponse.success("Asset history retrieved successfully", history)
        );
    }
}
