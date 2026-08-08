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
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebAuthnService 단위 테스트.
 *
 * <p>{@code relyingParty} 필드는 {@code @PostConstruct init()}에서만 초기화되므로
 * 테스트에서는 {@link ReflectionTestUtils}로 mock {@link RelyingParty}를 직접 주입한다.
 * 등록/로그인 ceremony state({@code registrationFlows}/{@code assertionFlows})는
 * private 필드이자 private nested record({@code FlowEntry})이므로 리플렉션으로 직접 조작한다.
 * Yubico SDK의 정적 팩토리 메서드({@code fromJson}, {@code parseRegistrationResponseJson} 등)는
 * {@link MockedStatic}으로 대체해 실제 WebAuthn JSON 페이로드 없이도 ceremony 완료 경로를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebAuthnService 단위 테스트")
class WebAuthnServiceTest {

    @Mock
    private AppCredentialRepository appCredentialRepository;

    @Mock
    private WebAuthnCredentialRepository credentialRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserKisAccountRepository kisAccountRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private WebAuthnService webAuthnService;

    @Mock
    private RelyingParty relyingParty;

    private Long userId;
    private User user;
    private UserKisAccount kisAccount;

    @BeforeEach
    void setUp() {
        userId = 1L;
        user = User.builder()
                .id(userId)
                .username("testuser")
                .password("encoded")
                .email("test@example.com")
                .name("테스트유저")
                .phone("01012345678")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build();
        kisAccount = UserKisAccount.builder()
                .id(10L)
                .user(user)
                .accountNumber("12345678-01")
                .accountProductCode("01")
                .appKey("ENC_KEY")
                .appSecret("ENC_SECRET")
                .isVerified(true)
                .build();

        ReflectionTestUtils.setField(webAuthnService, "relyingParty", relyingParty);
    }

    // ─── 리플렉션 헬퍼: private FlowEntry / flow map 직접 조작 ──────────────────

    private static Object newFlowEntry(String stateJson, long expiresAtMillis) throws Exception {
        Class<?> flowEntryClass = Class.forName("com.inbeom.apiserver.service.WebAuthnService$FlowEntry");
        Constructor<?> ctor = flowEntryClass.getDeclaredConstructor(String.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(stateJson, expiresAtMillis);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flowMap(String fieldName) throws Exception {
        Field field = WebAuthnService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Object>) field.get(webAuthnService);
    }

    private void putValidRegistrationFlow(String flowId) throws Exception {
        flowMap("registrationFlows").put(flowId, newFlowEntry("{\"stub\":true}", System.currentTimeMillis() + 60_000));
    }

    private void putExpiredRegistrationFlow(String flowId) throws Exception {
        flowMap("registrationFlows").put(flowId, newFlowEntry("{\"stub\":true}", System.currentTimeMillis() - 1_000));
    }

    private void putValidAssertionFlow(String flowId) throws Exception {
        flowMap("assertionFlows").put(flowId, newFlowEntry("{\"stub\":true}", System.currentTimeMillis() + 60_000));
    }

    private void putExpiredAssertionFlow(String flowId) throws Exception {
        flowMap("assertionFlows").put(flowId, newFlowEntry("{\"stub\":true}", System.currentTimeMillis() - 1_000));
    }

    // ─── startRegistration ──────────────────────────────────────────────────

    @Nested
    @DisplayName("startRegistration")
    class StartRegistrationTest {

        @Test
        @DisplayName("정상 시작 - flowId 발급 및 ceremony state 저장")
        void startRegistration_Success() throws Exception {
            // Given
            PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
            when(relyingParty.startRegistration(any())).thenReturn(options);
            when(options.toJson()).thenReturn("{\"options\":true}");
            when(options.toCredentialsCreateJson()).thenReturn("{\"credentialsCreateJson\":true}");

            // When
            WebAuthnStartResponse response = webAuthnService.startRegistration(userId, "testuser");

            // Then
            assertThat(response.getFlowId()).isNotBlank();
            assertThat(response.getOptions()).isEqualTo("{\"credentialsCreateJson\":true}");
            assertThat(flowMap("registrationFlows")).containsKey(response.getFlowId());
        }
    }

    // ─── finishRegistration ─────────────────────────────────────────────────

    @Nested
    @DisplayName("finishRegistration")
    class FinishRegistrationTest {

        @Test
        @DisplayName("flow 를 찾을 수 없으면 INVALID_INPUT_VALUE")
        void finishRegistration_FlowNotFound_Throws() {
            // When / Then
            assertThatThrownBy(() -> webAuthnService.finishRegistration(userId, "unknown-flow", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("flow 가 만료되었으면 INVALID_INPUT_VALUE")
        void finishRegistration_FlowExpired_Throws() throws Exception {
            // Given
            putExpiredRegistrationFlow("expired-flow");

            // When / Then
            assertThatThrownBy(() -> webAuthnService.finishRegistration(userId, "expired-flow", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("사용자를 찾을 수 없으면 USER_NOT_FOUND")
        void finishRegistration_UserNotFound_Throws() throws Exception {
            // Given
            putValidRegistrationFlow("flow-1");
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> webAuthnService.finishRegistration(userId, "flow-1", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 완료 - 새 credential 저장")
        void finishRegistration_Success_SavesCredential() throws Exception {
            // Given
            putValidRegistrationFlow("flow-1");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            PublicKeyCredentialDescriptor keyId = mock(PublicKeyCredentialDescriptor.class);
            when(keyId.getId()).thenReturn(new ByteArray("cred-id".getBytes()));
            when(keyId.getTransports()).thenReturn(Optional.empty());

            RegistrationResult result = mock(RegistrationResult.class);
            when(result.getKeyId()).thenReturn(keyId);
            when(result.getPublicKeyCose()).thenReturn(new ByteArray("pub-key".getBytes()));
            when(result.getSignatureCount()).thenReturn(0L);

            when(relyingParty.finishRegistration(any())).thenReturn(result);
            when(credentialRepository.findByCredentialId(anyString())).thenReturn(Optional.empty());

            try (MockedStatic<PublicKeyCredentialCreationOptions> optionsStatic =
                         mockStatic(PublicKeyCredentialCreationOptions.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class)) {
                optionsStatic.when(() -> PublicKeyCredentialCreationOptions.fromJson(anyString()))
                        .thenReturn(mock(PublicKeyCredentialCreationOptions.class));
                pkcStatic.when(() -> PublicKeyCredential.parseRegistrationResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));

                // When
                webAuthnService.finishRegistration(userId, "flow-1", "{}");
            }

            // Then
            verify(credentialRepository, times(1)).save(any(WebAuthnCredential.class));
        }

        @Test
        @DisplayName("이미 등록된 credential 이면 저장을 건너뛴다")
        void finishRegistration_AlreadyRegistered_SkipsSave() throws Exception {
            // Given
            putValidRegistrationFlow("flow-1");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            PublicKeyCredentialDescriptor keyId = mock(PublicKeyCredentialDescriptor.class);
            when(keyId.getId()).thenReturn(new ByteArray("cred-id".getBytes()));

            RegistrationResult result = mock(RegistrationResult.class);
            when(result.getKeyId()).thenReturn(keyId);

            when(relyingParty.finishRegistration(any())).thenReturn(result);
            when(credentialRepository.findByCredentialId(anyString()))
                    .thenReturn(Optional.of(WebAuthnCredential.builder().id(99L).build()));

            try (MockedStatic<PublicKeyCredentialCreationOptions> optionsStatic =
                         mockStatic(PublicKeyCredentialCreationOptions.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class)) {
                optionsStatic.when(() -> PublicKeyCredentialCreationOptions.fromJson(anyString()))
                        .thenReturn(mock(PublicKeyCredentialCreationOptions.class));
                pkcStatic.when(() -> PublicKeyCredential.parseRegistrationResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));

                // When
                webAuthnService.finishRegistration(userId, "flow-1", "{}");
            }

            // Then
            verify(credentialRepository, never()).save(any());
        }

        @Test
        @DisplayName("RelyingParty 검증 실패 시 INVALID_INPUT_VALUE")
        void finishRegistration_VerificationFailed_Throws() throws Exception {
            // Given
            putValidRegistrationFlow("flow-1");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(relyingParty.finishRegistration(any()))
                    .thenThrow(new RegistrationFailedException(new IllegalArgumentException("bad attestation")));

            try (MockedStatic<PublicKeyCredentialCreationOptions> optionsStatic =
                         mockStatic(PublicKeyCredentialCreationOptions.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class)) {
                optionsStatic.when(() -> PublicKeyCredentialCreationOptions.fromJson(anyString()))
                        .thenReturn(mock(PublicKeyCredentialCreationOptions.class));
                pkcStatic.when(() -> PublicKeyCredential.parseRegistrationResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));

                // When / Then
                assertThatThrownBy(() -> webAuthnService.finishRegistration(userId, "flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
            }

            verify(credentialRepository, never()).save(any());
        }
    }

    // ─── startAssertion ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("startAssertion")
    class StartAssertionTest {

        @Test
        @DisplayName("정상 시작 - flowId 발급 및 ceremony state 저장")
        void startAssertion_Success() throws Exception {
            // Given
            AssertionRequest request = mock(AssertionRequest.class);
            when(relyingParty.startAssertion(any())).thenReturn(request);
            when(request.toJson()).thenReturn("{\"request\":true}");
            when(request.toCredentialsGetJson()).thenReturn("{\"credentialsGetJson\":true}");

            // When
            WebAuthnStartResponse response = webAuthnService.startAssertion();

            // Then
            assertThat(response.getFlowId()).isNotBlank();
            assertThat(response.getOptions()).isEqualTo("{\"credentialsGetJson\":true}");
            assertThat(flowMap("assertionFlows")).containsKey(response.getFlowId());
        }
    }

    // ─── finishAssertion ────────────────────────────────────────────────────

    @Nested
    @DisplayName("finishAssertion")
    class FinishAssertionTest {

        @Test
        @DisplayName("flow 를 찾을 수 없으면 INVALID_INPUT_VALUE")
        void finishAssertion_FlowNotFound_Throws() {
            assertThatThrownBy(() -> webAuthnService.finishAssertion("unknown-flow", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("flow 가 만료되었으면 INVALID_INPUT_VALUE")
        void finishAssertion_FlowExpired_Throws() throws Exception {
            putExpiredAssertionFlow("expired-flow");

            assertThatThrownBy(() -> webAuthnService.finishAssertion("expired-flow", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("서명 검증 실패 시 INVALID_CREDENTIALS")
        void finishAssertion_VerificationFailed_Throws() throws Exception {
            putValidAssertionFlow("flow-1");
            when(relyingParty.finishAssertion(any()))
                    .thenThrow(new AssertionFailedException("signature invalid"));

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));

                assertThatThrownBy(() -> webAuthnService.finishAssertion("flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
            }
        }

        @Test
        @DisplayName("isSuccess() 가 false 이면 INVALID_CREDENTIALS")
        void finishAssertion_NotSuccessful_Throws() throws Exception {
            putValidAssertionFlow("flow-1");
            AssertionResult result = mock(AssertionResult.class);
            when(result.isSuccess()).thenReturn(false);
            when(relyingParty.finishAssertion(any())).thenReturn(result);

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));

                assertThatThrownBy(() -> webAuthnService.finishAssertion("flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
            }
        }

        @Test
        @DisplayName("userHandle 을 해석할 수 없으면 INVALID_CREDENTIALS")
        void finishAssertion_UnresolvedUserHandle_Throws() throws Exception {
            putValidAssertionFlow("flow-1");

            RegisteredCredential credential = mock(RegisteredCredential.class);
            when(credential.getUserHandle()).thenReturn(new ByteArray("handle".getBytes()));

            AssertionResult result = mock(AssertionResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getCredential()).thenReturn(credential);
            when(relyingParty.finishAssertion(any())).thenReturn(result);

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class);
                 MockedStatic<AppCredentialRepository> repoStatic = mockStatic(AppCredentialRepository.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));
                repoStatic.when(() -> AppCredentialRepository.userIdFromHandle(any()))
                        .thenReturn(Optional.empty());

                assertThatThrownBy(() -> webAuthnService.finishAssertion("flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
            }
        }

        @Test
        @DisplayName("해석된 userId 의 사용자가 없으면 USER_NOT_FOUND")
        void finishAssertion_UserNotFound_Throws() throws Exception {
            putValidAssertionFlow("flow-1");

            RegisteredCredential credential = mock(RegisteredCredential.class);
            when(credential.getUserHandle()).thenReturn(new ByteArray("handle".getBytes()));

            AssertionResult result = mock(AssertionResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getCredential()).thenReturn(credential);
            when(relyingParty.finishAssertion(any())).thenReturn(result);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class);
                 MockedStatic<AppCredentialRepository> repoStatic = mockStatic(AppCredentialRepository.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));
                repoStatic.when(() -> AppCredentialRepository.userIdFromHandle(any()))
                        .thenReturn(Optional.of(userId));

                assertThatThrownBy(() -> webAuthnService.finishAssertion("flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("KIS 계좌가 없으면 KIS_ACCOUNT_NOT_FOUND")
        void finishAssertion_KisAccountNotFound_Throws() throws Exception {
            putValidAssertionFlow("flow-1");

            RegisteredCredential credential = mock(RegisteredCredential.class);
            when(credential.getUserHandle()).thenReturn(new ByteArray("handle".getBytes()));
            when(credential.getCredentialId()).thenReturn(new ByteArray("cred-id".getBytes()));

            AssertionResult result = mock(AssertionResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getCredential()).thenReturn(credential);
            when(relyingParty.finishAssertion(any())).thenReturn(result);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(kisAccountRepository.findByUser(user)).thenReturn(Optional.empty());

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class);
                 MockedStatic<AppCredentialRepository> repoStatic = mockStatic(AppCredentialRepository.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));
                repoStatic.when(() -> AppCredentialRepository.userIdFromHandle(any()))
                        .thenReturn(Optional.of(userId));

                assertThatThrownBy(() -> webAuthnService.finishAssertion("flow-1", "{}"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_ACCOUNT_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("정상 완료 - 로그인 토큰 발급 및 기존 refresh 토큰 revoke")
        void finishAssertion_Success_IssuesLoginTokens() throws Exception {
            putValidAssertionFlow("flow-1");

            RegisteredCredential credential = mock(RegisteredCredential.class);
            when(credential.getUserHandle()).thenReturn(new ByteArray("handle".getBytes()));
            when(credential.getCredentialId()).thenReturn(new ByteArray("cred-id".getBytes()));

            AssertionResult result = mock(AssertionResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getCredential()).thenReturn(credential);
            when(result.getSignatureCount()).thenReturn(5L);
            when(relyingParty.finishAssertion(any())).thenReturn(result);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(kisAccountRepository.findByUser(user)).thenReturn(Optional.of(kisAccount));
            WebAuthnCredential storedCredential = WebAuthnCredential.builder().id(7L).signatureCount(4L).build();
            when(credentialRepository.findByCredentialId(anyString())).thenReturn(Optional.of(storedCredential));

            RefreshToken existingActive = RefreshToken.builder().id(5L).user(user).token("old").build();
            when(refreshTokenRepository.findByUserAndRevokedAtIsNull(user)).thenReturn(Optional.of(existingActive));
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

            when(jwtTokenProvider.generateAccessToken(user.getUsername(), userId, kisAccount.getId()))
                    .thenReturn("access-token");
            when(jwtTokenProvider.generateRefreshToken(user.getUsername())).thenReturn("refresh-token");
            when(jwtTokenProvider.getAccessTokenExpiration()).thenReturn(3_600_000L);
            when(jwtTokenProvider.getRefreshTokenExpiration()).thenReturn(86_400_000L);

            try (MockedStatic<AssertionRequest> requestStatic = mockStatic(AssertionRequest.class);
                 MockedStatic<PublicKeyCredential> pkcStatic = mockStatic(PublicKeyCredential.class);
                 MockedStatic<AppCredentialRepository> repoStatic = mockStatic(AppCredentialRepository.class)) {
                requestStatic.when(() -> AssertionRequest.fromJson(anyString()))
                        .thenReturn(mock(AssertionRequest.class));
                pkcStatic.when(() -> PublicKeyCredential.parseAssertionResponseJson(anyString()))
                        .thenReturn(mock(PublicKeyCredential.class));
                repoStatic.when(() -> AppCredentialRepository.userIdFromHandle(any()))
                        .thenReturn(Optional.of(userId));

                // When
                LoginResponse response = webAuthnService.finishAssertion("flow-1", "{}");

                // Then
                assertThat(response.getAccessToken()).isEqualTo("access-token");
                assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
                assertThat(response.getTokenType()).isEqualTo("Bearer");
                assertThat(response.getUser().getUsername()).isEqualTo("testuser");
            }

            assertThat(storedCredential.getSignatureCount()).isEqualTo(5L);
            assertThat(existingActive.getRevokedAt()).isNotNull();
            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        }
    }
}
