package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.RefreshToken;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.dto.auth.*;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import com.inbeom.apiserver.util.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jasypt.encryption.StringEncryptor;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 테스트")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserKisAccountRepository kisAccountRepository;

    @Mock
    private UserTradeConfigRepository tradeConfigRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringEncryptor jasyptStringEncryptor;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        // Test User 생성
        testUser = User.builder()
                .id(1L)
                .username("testuser")
                .password("encodedPassword")
                .email("test@example.com")
                .name("테스트유저")
                .phone("01012345678")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build();

        // Register Request 생성
        registerRequest = RegisterRequest.builder()
                .username("newuser")
                .password("password123")
                .passwordConfirm("password123")
                .email("new@example.com")
                .name("신규유저")
                .phone("01098765432")
                .birthDate(LocalDate.of(1995, 5, 15))
                .build();

        // Login Request 생성
        loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("password123")
                .build();
    }

    @Nested
    @DisplayName("회원가입 테스트")
    class RegisterTest {

        @Test
        @DisplayName("정상 회원가입 - KIS 계정 없이")
        void register_Success_WithoutKisAccount() {
            // given
            given(userRepository.existsByUsername(anyString())).willReturn(false);
            given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());
            given(passwordEncoder.encode(anyString())).willReturn("encodedPassword");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(tradeConfigRepository.save(any(UserTradeConfig.class))).willReturn(new UserTradeConfig());

            // when
            RegisterResponse response = authService.register(registerRequest);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getEmail()).isEqualTo("test@example.com");

            then(userRepository).should(times(1)).save(any(User.class));
            then(tradeConfigRepository).should(times(1)).save(any(UserTradeConfig.class));
            then(kisAccountRepository).should(never()).save(any(UserKisAccount.class));
        }

        @Test
        @DisplayName("정상 회원가입 - KIS 계정 포함")
        void register_Success_WithKisAccount() {
            // given
            RegisterRequest.KisAccountInfo kisAccountInfo = new RegisterRequest.KisAccountInfo(
                    "12345678-01",
                    "test-app-key",
                    "test-app-secret",
                    "MOCK"
            );
            registerRequest.setKisAccount(kisAccountInfo);

            given(userRepository.existsByUsername(anyString())).willReturn(false);
            given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());
            given(passwordEncoder.encode(anyString())).willReturn("encodedPassword");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(kisAccountRepository.save(any(UserKisAccount.class))).willReturn(new UserKisAccount());
            given(tradeConfigRepository.save(any(UserTradeConfig.class))).willReturn(new UserTradeConfig());
            given(jasyptStringEncryptor.encrypt("test-app-key")).willReturn("ENC_APP_KEY");
            given(jasyptStringEncryptor.encrypt("test-app-secret")).willReturn("ENC_APP_SECRET");

            // when
            RegisterResponse response = authService.register(registerRequest);

            // then
            assertThat(response).isNotNull();
            ArgumentCaptor<UserKisAccount> captor = ArgumentCaptor.forClass(UserKisAccount.class);
            then(kisAccountRepository).should(times(1)).save(captor.capture());

            // KIS 자격증명은 평문으로 저장되면 안 된다 (Jasypt 암호화 후 저장).
            UserKisAccount saved = captor.getValue();
            assertThat(saved.getAppKey()).isEqualTo("ENC_APP_KEY");
            assertThat(saved.getAppSecret()).isEqualTo("ENC_APP_SECRET");
            assertThat(saved.getAppKey()).isNotEqualTo("test-app-key");
            assertThat(saved.getAppSecret()).isNotEqualTo("test-app-secret");
        }

        @Test
        @DisplayName("회원가입 실패 - 비밀번호 확인 불일치")
        void register_Fail_PasswordMismatch() {
            // given
            registerRequest.setPasswordConfirm("differentPassword");

            // when & then
            assertThatThrownBy(() -> authService.register(registerRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Password confirmation does not match");

            then(userRepository).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("회원가입 실패 - 중복 username")
        void register_Fail_DuplicateUsername() {
            // given
            given(userRepository.existsByUsername(anyString())).willReturn(true);

            // when & then
            assertThatThrownBy(() -> authService.register(registerRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Username already exists");

            then(userRepository).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("회원가입 실패 - 중복 email")
        void register_Fail_DuplicateEmail() {
            // given
            given(userRepository.existsByUsername(anyString())).willReturn(false);
            given(userRepository.findByEmail(anyString())).willReturn(Optional.of(testUser));

            // when & then
            assertThatThrownBy(() -> authService.register(registerRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Email already exists");

            then(userRepository).should(never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("로그인 테스트")
    class LoginTest {

        private UserKisAccount testKisAccount;

        @BeforeEach
        void setUp() {
            testKisAccount = UserKisAccount.builder()
                    .id(10L)
                    .user(testUser)
                    .accountNumber("12345678-01")
                    .accountProductCode("01")
                    .appKey("ENCRYPTED_APP_KEY")
                    .appSecret("ENCRYPTED_APP_SECRET")
                    .isVerified(true)
                    .build();
        }

        @Test
        @DisplayName("정상 로그인 - JWT에 kisAccountId 포함")
        void login_Success_WithKisAccountIdInJwt() {
            // given
            String accessToken = "access-token";
            String refreshToken = "refresh-token";
            long expiresIn = 3600000L;

            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
            given(kisAccountRepository.findByUser(any(User.class))).willReturn(Optional.of(testKisAccount));
            given(jwtTokenProvider.generateAccessToken(anyString(), anyLong(), anyLong())).willReturn(accessToken);
            given(jwtTokenProvider.generateRefreshToken(anyString())).willReturn(refreshToken);
            given(jwtTokenProvider.getAccessTokenExpiration()).willReturn(expiresIn);
            given(jwtTokenProvider.getRefreshTokenExpiration()).willReturn(86400000L);
            given(refreshTokenRepository.findByUserAndRevokedAtIsNull(any(User.class)))
                    .willReturn(Optional.empty());
            given(refreshTokenRepository.save(any(RefreshToken.class)))
                    .willReturn(new RefreshToken());

            // when
            LoginResponse response = authService.login(loginRequest);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo(accessToken);
            assertThat(response.getRefreshToken()).isEqualTo(refreshToken);
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getExpiresIn()).isEqualTo(expiresIn);
            assertThat(response.getUser()).isNotNull();
            assertThat(response.getUser().getUsername()).isEqualTo("testuser");

            // Verify JWT was generated with userId=1L and kisAccountId=10L
            then(jwtTokenProvider).should(times(1))
                    .generateAccessToken(eq("testuser"), eq(1L), eq(10L));
            then(refreshTokenRepository).should(times(1)).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("로그인 실패 - KIS 계정 없음")
        void login_Fail_KisAccountNotFound() {
            // given
            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
            given(kisAccountRepository.findByUser(any(User.class))).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.login(loginRequest))
                    .isInstanceOf(KisAccountNotFoundException.class)
                    .hasMessageContaining("KIS account not found");

            then(jwtTokenProvider).should(never()).generateAccessToken(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("로그인 실패 - 존재하지 않는 사용자")
        void login_Fail_UserNotFound() {
            // given
            given(userRepository.findByUsername(anyString())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.login(loginRequest))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid username or password");

            then(jwtTokenProvider).should(never()).generateAccessToken(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("로그인 실패 - 잘못된 비밀번호")
        void login_Fail_InvalidPassword() {
            // given
            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

            // when & then
            assertThatThrownBy(() -> authService.login(loginRequest))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid username or password");

            then(jwtTokenProvider).should(never()).generateAccessToken(anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("비밀번호 재설정 테스트")
    class ResetPasswordTest {

        private ResetPasswordRequest resetRequest;

        @BeforeEach
        void setUp() {
            resetRequest = ResetPasswordRequest.builder()
                    .username("testuser")
                    .phone("01012345678")
                    .newPassword("newPassword123")
                    .passwordConfirm("newPassword123")
                    .build();
        }

        @Test
        @DisplayName("정상 비밀번호 재설정")
        void resetPassword_Success() {
            // given
            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));
            given(passwordEncoder.encode(anyString())).willReturn("newEncodedPassword");
            given(userRepository.save(any(User.class))).willReturn(testUser);

            // when
            authService.resetPassword(resetRequest);

            // then
            then(userRepository).should(times(1)).save(any(User.class));
        }

        @Test
        @DisplayName("비밀번호 재설정 실패 - 비밀번호 확인 불일치")
        void resetPassword_Fail_PasswordMismatch() {
            // given
            resetRequest.setPasswordConfirm("differentPassword");

            // when & then
            assertThatThrownBy(() -> authService.resetPassword(resetRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Password confirmation does not match");

            then(userRepository).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("비밀번호 재설정 실패 - 존재하지 않는 사용자")
        void resetPassword_Fail_UserNotFound() {
            // given
            given(userRepository.findByUsername(anyString())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.resetPassword(resetRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found or phone number mismatch");

            then(userRepository).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("비밀번호 재설정 실패 - 전화번호 불일치")
        void resetPassword_Fail_PhoneMismatch() {
            // given
            resetRequest.setPhone("01099999999");
            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));

            // when & then
            assertThatThrownBy(() -> authService.resetPassword(resetRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found or phone number mismatch");

            then(userRepository).should(never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("중복 확인 테스트")
    class CheckAvailabilityTest {

        @Test
        @DisplayName("username 사용 가능")
        void checkUsername_Available() {
            // given
            given(userRepository.existsByUsername(anyString())).willReturn(false);

            // when
            CheckAvailabilityResponse response = authService.checkUsername("newuser");

            // then
            assertThat(response).isNotNull();
            assertThat(response.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("username 사용 불가능")
        void checkUsername_NotAvailable() {
            // given
            given(userRepository.existsByUsername(anyString())).willReturn(true);

            // when
            CheckAvailabilityResponse response = authService.checkUsername("existinguser");

            // then
            assertThat(response).isNotNull();
            assertThat(response.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("email 사용 가능")
        void checkEmail_Available() {
            // given
            given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());

            // when
            CheckAvailabilityResponse response = authService.checkEmail("new@example.com");

            // then
            assertThat(response).isNotNull();
            assertThat(response.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("email 사용 불가능")
        void checkEmail_NotAvailable() {
            // given
            given(userRepository.findByEmail(anyString())).willReturn(Optional.of(testUser));

            // when
            CheckAvailabilityResponse response = authService.checkEmail("existing@example.com");

            // then
            assertThat(response).isNotNull();
            assertThat(response.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("토큰 갱신 테스트")
    class RefreshTokenTest {

        private RefreshTokenRequest refreshRequest;
        private RefreshToken refreshToken;
        private UserKisAccount testKisAccount;

        @BeforeEach
        void setUp() {
            refreshRequest = RefreshTokenRequest.builder()
                    .refreshToken("valid-refresh-token")
                    .build();

            refreshToken = RefreshToken.builder()
                    .id(1L)
                    .user(testUser)
                    .token("valid-refresh-token")
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();

            testKisAccount = UserKisAccount.builder()
                    .id(10L)
                    .user(testUser)
                    .accountNumber("12345678-01")
                    .accountProductCode("01")
                    .appKey("ENCRYPTED_APP_KEY")
                    .appSecret("ENCRYPTED_APP_SECRET")
                    .isVerified(true)
                    .build();
        }

        @Test
        @DisplayName("정상 토큰 갱신 - 새 액세스 토큰에 kisAccountId 포함")
        void refreshToken_Success_WithKisAccountIdInNewToken() {
            // given
            String newAccessToken = "new-access-token";
            long expiresIn = 3600000L;

            given(jwtTokenProvider.validateToken(anyString())).willReturn(true);
            given(refreshTokenRepository.findByToken(anyString())).willReturn(Optional.of(refreshToken));
            given(jwtTokenProvider.getUsernameFromToken(anyString())).willReturn("testuser");
            given(userRepository.findByUsername(anyString())).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUser(any(User.class))).willReturn(Optional.of(testKisAccount));
            given(jwtTokenProvider.generateAccessToken(anyString(), anyLong(), anyLong())).willReturn(newAccessToken);
            given(jwtTokenProvider.getAccessTokenExpiration()).willReturn(expiresIn);

            // when
            RefreshTokenResponse response = authService.refreshToken(refreshRequest);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo(newAccessToken);
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getExpiresIn()).isEqualTo(expiresIn);

            // Verify new access token was generated with kisAccountId
            then(jwtTokenProvider).should(times(1))
                    .generateAccessToken(eq("testuser"), eq(1L), eq(10L));
        }

        @Test
        @DisplayName("토큰 갱신 실패 - 잘못된 토큰")
        void refreshToken_Fail_InvalidToken() {
            // given
            given(jwtTokenProvider.validateToken(anyString())).willReturn(false);

            // when & then
            assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Invalid refresh token");

            then(refreshTokenRepository).should(never()).findByToken(anyString());
        }

        @Test
        @DisplayName("토큰 갱신 실패 - 토큰을 찾을 수 없음")
        void refreshToken_Fail_TokenNotFound() {
            // given
            given(jwtTokenProvider.validateToken(anyString())).willReturn(true);
            given(refreshTokenRepository.findByToken(anyString())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Refresh token not found");

            then(jwtTokenProvider).should(never()).generateAccessToken(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("토큰 갱신 실패 - revoke된 토큰")
        void refreshToken_Fail_RevokedToken() {
            // given
            refreshToken.revoke();
            given(jwtTokenProvider.validateToken(anyString())).willReturn(true);
            given(refreshTokenRepository.findByToken(anyString())).willReturn(Optional.of(refreshToken));

            // when & then
            assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Refresh token has been revoked");

            then(jwtTokenProvider).should(never()).generateAccessToken(anyString(), anyLong(), anyLong());
        }
    }
}
