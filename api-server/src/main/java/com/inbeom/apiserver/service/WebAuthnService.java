package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.RefreshToken;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.WebAuthnCredential;
import com.inbeom.apiserver.dto.auth.LoginResponse;
import com.inbeom.apiserver.dto.webauthn.WebAuthnStartResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.WebAuthnCredentialRepository;
import com.inbeom.apiserver.security.webauthn.AppCredentialRepository;
import com.inbeom.apiserver.util.JwtTokenProvider;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WebAuthn(패스키/생체) 등록·로그인 ceremony 오케스트레이션.
 *
 * <p>challenge/ceremony state 는 stateless(JWT) 앱이라 세션이 없으므로
 * in-memory {@link ConcurrentHashMap} + TTL(5분)에 보관한다. start 가 flowId 를 반환하고
 * finish 가 그 flowId 로 state 를 찾는다. <b>단일 인스턴스 MVP 전제</b> — 서버 재시작 또는
 * 멀티 인스턴스 환경에서는 진행 중이던 ceremony 가 유지되지 않는다.
 *
 * <p>로그인 성공 시 토큰 발급은 일반 로그인({@code AuthService.login})과 동일한 로직
 * (JwtTokenProvider access+refresh, 기존 refresh revoke 후 저장, 동일 LoginResponse shape)을 따른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthnService {

    /** ceremony state TTL (밀리초). */
    private static final long FLOW_TTL_MILLIS = 5 * 60 * 1000L;

    private final AppCredentialRepository appCredentialRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final UserKisAccountRepository kisAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${webauthn.rp-id}")
    private String rpId;

    @Value("${webauthn.rp-name}")
    private String rpName;

    @Value("${webauthn.origins}")
    private String origins;

    private RelyingParty relyingParty;

    /** 등록 ceremony state: flowId → 직렬화된 PublicKeyCredentialCreationOptions JSON. */
    private final ConcurrentHashMap<String, FlowEntry> registrationFlows = new ConcurrentHashMap<>();
    /** 로그인 ceremony state: flowId → 직렬화된 AssertionRequest JSON. */
    private final ConcurrentHashMap<String, FlowEntry> assertionFlows = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        Set<String> originSet = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
                .id(rpId)
                .name(rpName)
                .build();

        // origins 는 포트까지 정확히 일치해야 한다(allowOriginPort 기본 false). 프론트 origin 과 정확히 맞춰야
        // NotAllowedError 가 발생하지 않는다. usernameless 로그인은 startAssertion 에서 username 을 비워 동작.
        this.relyingParty = RelyingParty.builder()
                .identity(identity)
                .credentialRepository(appCredentialRepository)
                .origins(originSet)
                .build();

        log.info("WebAuthn RelyingParty initialized: rpId={}, origins={}", rpId, originSet);
    }

    // ============================================================
    // 등록 (로그인된 상태에서만 — userId 확정)
    // ============================================================

    /**
     * 등록 시작. 플랫폼 인증기 + discoverable(passkey) preferred, UV preferred 옵션을 만든다.
     *
     * @return flowId + toCredentialsCreateJson() 직렬화된 옵션 JSON
     */
    public WebAuthnStartResponse startRegistration(Long userId, String username) {
        purgeExpired();

        UserIdentity userIdentity = UserIdentity.builder()
                .name(username)
                .displayName(username)
                .id(AppCredentialRepository.userHandleFor(userId))
                .build();

        AuthenticatorSelectionCriteria selection = AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.PREFERRED)
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build();

        PublicKeyCredentialCreationOptions options = relyingParty.startRegistration(
                StartRegistrationOptions.builder()
                        .user(userIdentity)
                        .authenticatorSelection(selection)
                        .build());

        String flowId = UUID.randomUUID().toString();
        String optionsJson;
        try {
            registrationFlows.put(flowId, new FlowEntry(options.toJson(), now() + FLOW_TTL_MILLIS));
            optionsJson = options.toCredentialsCreateJson();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Failed to serialize registration options");
        }

        return WebAuthnStartResponse.builder()
                .flowId(flowId)
                .options(optionsJson)
                .build();
    }

    /**
     * 등록 완료. 클라이언트 응답을 검증하고 새 credential 을 저장한다.
     */
    @Transactional
    public void finishRegistration(Long userId, String flowId, String credentialJson) {
        purgeExpired();

        FlowEntry entry = registrationFlows.remove(flowId);
        if (entry == null || entry.isExpired(now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Registration flow expired or not found");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found"));

        PublicKeyCredentialCreationOptions options;
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc;
        try {
            options = PublicKeyCredentialCreationOptions.fromJson(entry.stateJson());
            pkc = PublicKeyCredential.parseRegistrationResponseJson(credentialJson);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Malformed WebAuthn registration response");
        }

        RegistrationResult result;
        try {
            result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(options)
                    .response(pkc)
                    .build());
        } catch (RegistrationFailedException e) {
            log.warn("WebAuthn registration verification failed for userId={}: {}", userId, e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "WebAuthn registration verification failed");
        }

        String credentialId = result.getKeyId().getId().getBase64Url();

        // 동일 credential 재등록 방지(이미 존재하면 무시).
        if (credentialRepository.findByCredentialId(credentialId).isPresent()) {
            log.info("WebAuthn credential already registered, skipping: {}", credentialId);
            return;
        }

        String transports = result.getKeyId().getTransports()
                .map(set -> set.stream()
                        .map(t -> t.getId())
                        .collect(Collectors.joining(",")))
                .filter(s -> !s.isEmpty())
                .orElse(null);

        WebAuthnCredential credential = WebAuthnCredential.builder()
                .user(user)
                .credentialId(credentialId)
                .publicKeyCose(result.getPublicKeyCose().getBase64Url())
                .signatureCount(result.getSignatureCount())
                .transports(transports)
                .build();
        credentialRepository.save(credential);

        log.info("WebAuthn credential registered for userId={}, credentialId={}", userId, credentialId);
    }

    // ============================================================
    // 로그인 (usernameless / discoverable)
    // ============================================================

    /**
     * 로그인 시작 (usernameless). allowCredentials 를 비워 사용자가 아이디 입력 없이
     * 생체만으로 인증하고, 응답의 userHandle 로 사용자를 식별한다.
     */
    public WebAuthnStartResponse startAssertion() {
        purgeExpired();

        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build());

        String flowId = UUID.randomUUID().toString();
        String optionsJson;
        try {
            assertionFlows.put(flowId, new FlowEntry(request.toJson(), now() + FLOW_TTL_MILLIS));
            optionsJson = request.toCredentialsGetJson();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Failed to serialize assertion options");
        }

        return WebAuthnStartResponse.builder()
                .flowId(flowId)
                .options(optionsJson)
                .build();
    }

    /**
     * 로그인 완료. 서명을 검증하고 성공 시 일반 로그인과 동일한 Access+Refresh 토큰을 발급한다.
     */
    @Transactional
    public LoginResponse finishAssertion(String flowId, String credentialJson) {
        purgeExpired();

        FlowEntry entry = assertionFlows.remove(flowId);
        if (entry == null || entry.isExpired(now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Login flow expired or not found");
        }

        AssertionRequest request;
        PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
        try {
            request = AssertionRequest.fromJson(entry.stateJson());
            pkc = PublicKeyCredential.parseAssertionResponseJson(credentialJson);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Malformed WebAuthn login response");
        }

        AssertionResult result;
        try {
            result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(pkc)
                    .build());
        } catch (AssertionFailedException e) {
            log.warn("WebAuthn assertion verification failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "WebAuthn login verification failed");
        }

        if (!result.isSuccess()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "WebAuthn login was not successful");
        }

        // userHandle → userId 해석 (8바이트 big-endian).
        Long userId = AppCredentialRepository.userIdFromHandle(result.getCredential().getUserHandle())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Unresolved user handle"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // signatureCount 업데이트 (replay 방지 카운터).
        credentialRepository.findByCredentialId(result.getCredential().getCredentialId().getBase64Url())
                .ifPresent(cred -> cred.setSignatureCount(result.getSignatureCount()));

        return issueLoginTokens(user);
    }

    // ============================================================
    // 토큰 발급 (AuthService.login 과 동일 로직 재현)
    // ============================================================

    private LoginResponse issueLoginTokens(User user) {
        UserKisAccount kisAccount = kisAccountRepository.findByUser(user)
                .orElseThrow(() -> new BusinessException(ErrorCode.KIS_ACCOUNT_NOT_FOUND, "KIS account not found"));

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUsername(), user.getId(), kisAccount.getId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        // 기존 활성 refresh 토큰 revoke 후 새 토큰 저장 (일반 로그인과 동일 정책).
        refreshTokenRepository.findByUserAndRevokedAtIsNull(user)
                .ifPresent(rt -> {
                    rt.revoke();
                    refreshTokenRepository.save(rt);
                });

        RefreshToken stored = RefreshToken.builder()
                .user(user)
                .token(refreshToken)
                .expiresAt(java.time.LocalDateTime.now()
                        .plusSeconds(jwtTokenProvider.getRefreshTokenExpiration() / 1000))
                .build();
        refreshTokenRepository.save(stored);

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

    // ============================================================
    // in-memory flow store
    // ============================================================

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private void purgeExpired() {
        long current = now();
        registrationFlows.values().removeIf(e -> e.isExpired(current));
        assertionFlows.values().removeIf(e -> e.isExpired(current));
    }

    /** 직렬화된 ceremony state + 만료 시각(epoch millis). */
    private record FlowEntry(String stateJson, long expiresAtMillis) {
        boolean isExpired(long current) {
            return current > expiresAtMillis;
        }
    }
}
