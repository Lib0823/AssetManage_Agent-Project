package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.favorite.AddFavoriteRequest;
import com.inbeom.apiserver.dto.favorite.FavoriteResponse;
import com.inbeom.apiserver.service.FavoriteService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관심 종목 컨트롤러 (관심종목 화면 - FavoritesView). JWT 인증 필요.
 *
 * <p>목록/추가/삭제를 제공한다. 현재가는 공용 quote 헬퍼로 조회하며, 시세 비활성 시
 * 가격 null + notice 로 degrade 한다.
 */
@Slf4j
@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * GET /api/favorites
     * 관심 종목 목록 + 종목별 현재가/등락률
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<FavoriteResponse>>> getFavorites(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        List<FavoriteResponse> favorites = favoriteService.getFavorites(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Favorites retrieved successfully", favorites)
        );
    }

    /**
     * POST /api/favorites
     * 관심 종목 추가 (이미 등록된 종목은 멱등 처리)
     */
    @PostMapping
    public ResponseEntity<ApiResponse<FavoriteResponse>> addFavorite(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody AddFavoriteRequest request
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        FavoriteResponse favorite = favoriteService.addFavorite(
                userId, request.getStockCode(), request.getStockName(), request.getExchangeCode());

        return ResponseEntity.ok(
                ApiResponse.success("Favorite added successfully", favorite)
        );
    }

    /**
     * DELETE /api/favorites/{stockCode}
     * 관심 종목 삭제 (미등록 종목이어도 멱등 처리)
     */
    @DeleteMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<Void>> removeFavorite(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String stockCode
    ) {
        String token = authHeader.substring(7);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        favoriteService.removeFavorite(userId, stockCode);

        return ResponseEntity.ok(
                ApiResponse.success("Favorite removed successfully", null)
        );
    }
}
