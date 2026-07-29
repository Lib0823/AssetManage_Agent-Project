package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.auth.LoginResponse;
import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.dto.webauthn.WebAuthnFinishRequest;
import com.inbeom.apiserver.dto.webauthn.WebAuthnStartResponse;
import com.inbeom.apiserver.service.WebAuthnService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * WebAuthn(패스키/생체) 등록·로그인 엔드포인트.
 *
 * <ul>
 *   <li>{@code /register/**} : JWT 필요(로그인된 상태에서만 자격증명 등록).</li>
 *   <li>{@code /login/**}    : 공개(SecurityConfig 에서 permitAll). usernameless 로그인.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/auth/webauthn")
@RequiredArgsConstructor
public class WebAuthnController {

    private final WebAuthnService webAuthnService;
    private final JwtTokenProvider jwtTokenProvider;

    /** 등록 시작 (JWT 필요). */
    @PostMapping("/register/start")
    public ApiResponse<WebAuthnStartResponse> registerStart(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = authHeader.substring(7);  // Remove "Bearer "
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        String username = jwtTokenProvider.getUsernameFromToken(token);

        WebAuthnStartResponse response = webAuthnService.startRegistration(userId, username);
        return ApiResponse.success("WebAuthn registration started", response);
    }

    /** 등록 완료 (JWT 필요). */
    @PostMapping("/register/finish")
    public ApiResponse<Void> registerFinish(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody WebAuthnFinishRequest request
    ) {
        String token = authHeader.substring(7);  // Remove "Bearer "
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        webAuthnService.finishRegistration(userId, request.getFlowId(), request.getCredential());
        return ApiResponse.success("WebAuthn credential registered", null);
    }

    /** 로그인 시작 (공개, usernameless). */
    @PostMapping("/login/start")
    public ApiResponse<WebAuthnStartResponse> loginStart() {
        WebAuthnStartResponse response = webAuthnService.startAssertion();
        return ApiResponse.success("WebAuthn login started", response);
    }

    /** 로그인 완료 (공개). 성공 시 일반 로그인과 동일한 LoginResponse(Access+Refresh) 반환. */
    @PostMapping("/login/finish")
    public ApiResponse<LoginResponse> loginFinish(
            @Valid @RequestBody WebAuthnFinishRequest request
    ) {
        LoginResponse response = webAuthnService.finishAssertion(request.getFlowId(), request.getCredential());
        return ApiResponse.success("WebAuthn login successful", response);
    }
}
