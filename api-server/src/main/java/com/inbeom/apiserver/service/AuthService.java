package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.RefreshToken;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.dto.auth.*;
import com.inbeom.apiserver.dto.kis.KisTokenRequest;
import com.inbeom.apiserver.dto.kis.KisTokenResponse;
import com.inbeom.apiserver.exception.*;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import com.inbeom.apiserver.util.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserKisAccountRepository kisAccountRepository;
    private final UserTradeConfigRepository tradeConfigRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringEncryptor jasyptStringEncryptor;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${kis.base-url}")
    private String kisBaseUrl;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 비밀번호 확인 검증
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH, "Password confirmation does not match");
        }

        // 중복 확인
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_DUPLICATE, "Username already exists");
        }
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATE, "Email already exists");
        }

        // User 생성
        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .name(request.getName())
                .phone(request.getPhone())
                .birthDate(request.getBirthDate())
                .build();
        user = userRepository.save(user);

        // KIS Account 생성
        if (request.getKisAccount() != null) {
            UserKisAccount kisAccount = UserKisAccount.builder()
                    .user(user)
                    .accountNumber(request.getKisAccount().getAccountNumber())
                    .appKey(jasyptStringEncryptor.encrypt(request.getKisAccount().getAppKey()))
                    .appSecret(jasyptStringEncryptor.encrypt(request.getKisAccount().getAppSecret()))
                    .isVerified(false)
                    .build();
            kisAccountRepository.save(kisAccount);
        }

        // Trade Config 생성 (기본값)
        UserTradeConfig tradeConfig = UserTradeConfig.builder()
                .user(user)
                .orderAmount(1000000L)
                .maxHoldings(10)
                .orderType("market")
                .isActive(false)
                .build();
        tradeConfigRepository.save(tradeConfig);

        return RegisterResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 사용자 조회
        User user = userRepository.findByUsername(request.getUsername())
                 .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        // KIS Account 조회
        UserKisAccount kisAccount = kisAccountRepository.findByUser(user)
                .orElseThrow(() -> new KisAccountNotFoundException(user.getId()));

        // JWT 토큰 생성 (userId, kisAccountId 포함)
        String accessToken = jwtTokenProvider.generateAccessToken(user.getUsername(), user.getId(), kisAccount.getId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        // RefreshToken 저장
        saveRefreshToken(user, refreshToken);

        // 응답 생성
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpiration())
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .name(user.getName())
                        .build())
                .build();
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        // 비밀번호 확인 검증
        if (!request.getNewPassword().equals(request.getPasswordConfirm())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH, "Password confirmation does not match");
        }

        // 사용자 조회 및 본인 인증 (username + phone)
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.PHONE_MISMATCH, "User not found or phone number mismatch"));

        if (!user.getPhone().equals(request.getPhone())) {
            throw new BusinessException(ErrorCode.PHONE_MISMATCH, "User not found or phone number mismatch");
        }

        // 비밀번호 변경
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public CheckAvailabilityResponse checkUsername(String username) {
        boolean available = !userRepository.existsByUsername(username);
        return CheckAvailabilityResponse.builder()
                .available(available)
                .build();
    }

    @Transactional(readOnly = true)
    public CheckAvailabilityResponse checkEmail(String email) {
        boolean available = userRepository.findByEmail(email).isEmpty();
        return CheckAvailabilityResponse.builder()
                .available(available)
                .build();
    }

    @Transactional
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshTokenValue = request.getRefreshToken();

        // 토큰 검증 (서명·만료 + type=refresh — 액세스 토큰으로는 재발급받을 수 없다)
        if (!jwtTokenProvider.validateRefreshToken(refreshTokenValue)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Invalid refresh token");
        }

        // RefreshToken 조회
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND, "Refresh token not found"));

        // 토큰이 revoke 되었는지 확인
        if (refreshToken.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED, "Refresh token has been revoked");
        }

        // 새로운 AccessToken 생성
        String username = jwtTokenProvider.getUsernameFromToken(refreshTokenValue);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));

        UserKisAccount kisAccount = kisAccountRepository.findByUser(user)
                .orElseThrow(() -> new KisAccountNotFoundException(user.getId()));

        String newAccessToken = jwtTokenProvider.generateAccessToken(username, user.getId(), kisAccount.getId());

        return RefreshTokenResponse.builder()
                .accessToken(newAccessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpiration())
                .build();
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        // RefreshToken 조회 및 revoke
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND, "Refresh token not found"));

        if (refreshToken.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED, "Refresh token has already been revoked");
        }

        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);
    }

    /**
     * KIS 계정 검증 (AppKey, AppSecret으로 OAuth 토큰 발급 시도)
     */
    public ValidateKisAccountResponse validateKisAccount(ValidateKisAccountRequest request) {
        String url = kisBaseUrl + "/oauth2/tokenP";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        KisTokenRequest body = KisTokenRequest.builder()
                .grantType("client_credentials")
                .appKey(request.getAppKey())
                .appSecret(request.getAppSecret())
                .build();

        HttpEntity<KisTokenRequest> httpRequest = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<KisTokenResponse> response = restTemplate.postForEntity(url, httpRequest, KisTokenResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                log.info("KIS account validation successful");
                return ValidateKisAccountResponse.success();
            } else {
                log.warn("KIS account validation failed with status: {}", response.getStatusCode());
                return ValidateKisAccountResponse.failure(
                        "KIS 인증에 실패했습니다",
                        "INVALID_RESPONSE"
                );
            }
        } catch (HttpClientErrorException e) {
            // 4xx: Invalid credentials
            log.error("KIS account validation failed (4xx): status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());

            String message = "KIS APP Key 또는 APP Secret이 올바르지 않습니다";
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                message = "KIS 인증 정보가 유효하지 않습니다";
            } else if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                message = "KIS 요청 형식이 올바르지 않습니다";
            } else if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                message = "KIS API 서버 오류입니다. 잠시 후 다시 시도해주세요";
            }

            return ValidateKisAccountResponse.failure(message, "INVALID_CREDENTIALS");
        } catch (Exception e) {
            // Network or other errors
            log.error("KIS account validation error", e);
            return ValidateKisAccountResponse.failure(
                    "KIS 서버 연결에 실패했습니다. 잠시 후 다시 시도해주세요",
                    "CONNECTION_ERROR"
            );
        }
    }

    private void saveRefreshToken(User user, String token) {
        // 기존 토큰 revoke
        refreshTokenRepository.findByUserAndRevokedAtIsNull(user)
                .ifPresent(rt -> {
                    rt.revoke();
                    refreshTokenRepository.save(rt);
                });

        // 새 토큰 저장
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtTokenProvider.getRefreshTokenExpiration() / 1000);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(token)
                .expiresAt(expiresAt)
                .build();

        refreshTokenRepository.save(refreshToken);
    }
}
