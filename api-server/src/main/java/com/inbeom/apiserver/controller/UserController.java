package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.user.*;
import com.inbeom.apiserver.service.UserService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * GET /api/users/me
     * Get user profile
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UserProfileResponse profile = userService.getUserProfile(userId);

        return ResponseEntity.ok(
                ApiResponse.success("User profile retrieved successfully", profile)
        );
    }

    /**
     * PUT /api/users/me
     * Update user profile
     */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateUserProfile(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UserProfileResponse profile = userService.updateUserProfile(userId, request);

        return ResponseEntity.ok(
                ApiResponse.success("User profile updated successfully", profile)
        );
    }

    /**
     * GET /api/users/settings
     * Get user settings
     */
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<UserSettingsResponse>> getUserSettings(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UserSettingsResponse settings = userService.getUserSettings(userId);

        return ResponseEntity.ok(
                ApiResponse.success("User settings retrieved successfully", settings)
        );
    }

    /**
     * PUT /api/users/settings
     * Update user settings
     */
    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<UserSettingsResponse>> updateUserSettings(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateUserSettingsRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UserSettingsResponse settings = userService.updateUserSettings(userId, request);

        return ResponseEntity.ok(
                ApiResponse.success("User settings updated successfully", settings)
        );
    }

    /**
     * DELETE /api/users/me
     * Delete user account (회원 탈퇴)
     */
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        log.info("User deletion requested: userId={}", userId);
        userService.deleteAccount(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Account deleted successfully", null)
        );
    }

    /**
     * GET /api/users/kis-account
     * Get user KIS account information
     */
    @GetMapping("/kis-account")
    public ResponseEntity<ApiResponse<KisAccountResponse>> getKisAccount(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        KisAccountResponse kisAccount = userService.getKisAccount(userId);

        return ResponseEntity.ok(
                ApiResponse.success("KIS account retrieved successfully", kisAccount)
        );
    }

    /**
     * PUT /api/users/kis-account
     * Update user KIS account information
     */
    @PutMapping("/kis-account")
    public ResponseEntity<ApiResponse<KisAccountResponse>> updateKisAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateKisAccountRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        KisAccountResponse kisAccount = userService.updateKisAccount(userId, request);

        return ResponseEntity.ok(
                ApiResponse.success("KIS account updated successfully", kisAccount)
        );
    }

    /**
     * GET /api/users/upbit-account
     *
     * <p>업비트 계좌 등록 상태. <b>Secret Key 는 어떤 형태로도 내려가지 않는다</b> — 응답 DTO 에
     * 필드 자체가 없고, Access Key 도 앞 4자만 남긴 마스킹으로만 나간다.
     * 미등록이어도 404 가 아니라 {@code registered=false} 다(설정 화면이 그 상태를 그려야 한다).
     */
    @GetMapping("/upbit-account")
    public ResponseEntity<ApiResponse<UpbitAccountResponse>> getUpbitAccount(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UpbitAccountResponse upbitAccount = userService.getUpbitAccount(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Upbit account retrieved successfully", upbitAccount)
        );
    }

    /**
     * PUT /api/users/upbit-account
     *
     * <p>등록·수정 겸용(upsert). <b>빈 값은 기존 키 유지</b>다 — 조회가 실제 키를 돌려주지 않으므로
     * 프론트가 되채울 수 없고, 빈 값을 삭제로 해석하면 한쪽만 고치려던 사용자가 다른 키를 잃는다.
     *
     * <p>키 유효성 검증은 이 저장이 겸한다. 별도의 {@code POST /auth/validate-upbit-account} 를
     * 두지 않는 이유는 {@code /auth/**} 가 permitAll 이라 그 자리에 두면 미인증 공개 검사기가
     * 되기 때문이다.
     */
    @PutMapping("/upbit-account")
    public ResponseEntity<ApiResponse<UpbitAccountResponse>> updateUpbitAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateUpbitAccountRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        UpbitAccountResponse upbitAccount = userService.updateUpbitAccount(userId, request);

        return ResponseEntity.ok(
                ApiResponse.success("Upbit account updated successfully", upbitAccount)
        );
    }

    /**
     * GET /api/users/trade-config
     * Get user trade configuration
     */
    @GetMapping("/trade-config")
    public ResponseEntity<ApiResponse<TradeConfigResponse>> getTradeConfig(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        TradeConfigResponse config = userService.getTradeConfig(userId);

        return ResponseEntity.ok(
                ApiResponse.success("Trade configuration retrieved successfully", config)
        );
    }

    /**
     * PUT /api/users/trade-config
     * Update user trade configuration
     */
    @PutMapping("/trade-config")
    public ResponseEntity<ApiResponse<TradeConfigResponse>> updateTradeConfig(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateTradeConfigRequest request
    ) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        TradeConfigResponse config = userService.updateTradeConfig(userId, request);

        return ResponseEntity.ok(
                ApiResponse.success("Trade configuration updated successfully", config)
        );
    }
}
