package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserSettings;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.dto.user.KisAccountResponse;
import com.inbeom.apiserver.dto.user.TradeConfigResponse;
import com.inbeom.apiserver.dto.user.UpdateKisAccountRequest;
import com.inbeom.apiserver.dto.user.UpdateTradeConfigRequest;
import com.inbeom.apiserver.dto.user.UpdateUserProfileRequest;
import com.inbeom.apiserver.dto.user.UpdateUserSettingsRequest;
import com.inbeom.apiserver.dto.user.UserProfileResponse;
import com.inbeom.apiserver.dto.user.UserSettingsResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserSettingsRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserTradeConfigRepository tradeConfigRepository;

    @Mock
    private UserKisAccountRepository kisAccountRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserSettingsRepository userSettingsRepository;

    @Mock
    private StringEncryptor jasyptStringEncryptor;

    /** JSON 직렬화는 진짜 동작(파싱 실패 경로 포함)을 검증해야 하므로 실제 ObjectMapper 를 쓴다. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                tradeConfigRepository,
                kisAccountRepository,
                refreshTokenRepository,
                userSettingsRepository,
                objectMapper,
                jasyptStringEncryptor);

        testUser = User.builder()
                .id(1L)
                .username("testuser")
                .password("encodedPassword")
                .email("test@example.com")
                .name("테스트유저")
                .phone("010-1234-5678")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("매매 설정 테스트")
    class TradeConfigTest {

        private UserTradeConfig config;

        @BeforeEach
        void setUp() {
            config = UserTradeConfig.builder()
                    .id(5L)
                    .user(testUser)
                    .orderAmount(1_000_000L)
                    .maxHoldings(10)
                    .orderType("market")
                    .isActive(false)
                    .build();
        }

        @Test
        @DisplayName("getTradeConfig - 저장된 설정을 응답 DTO 로 반환한다")
        void getTradeConfig_Success() {
            // Given
            given(tradeConfigRepository.findByUserId(1L)).willReturn(Optional.of(config));

            // When
            TradeConfigResponse response = userService.getTradeConfig(1L);

            // Then
            assertThat(response.getId()).isEqualTo(5L);
            assertThat(response.getOrderAmount()).isEqualTo(1_000_000L);
            assertThat(response.getMaxHoldings()).isEqualTo(10);
            assertThat(response.getOrderType()).isEqualTo("market");
            assertThat(response.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("getTradeConfig - 설정이 없으면 ENTITY_NOT_FOUND")
        void getTradeConfig_Fail_NotFound() {
            // Given
            given(tradeConfigRepository.findByUserId(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.getTradeConfig(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Trade configuration not found")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
        }

        @Test
        @DisplayName("updateTradeConfig - 요청값으로 갱신 후 저장한다")
        void updateTradeConfig_Success() {
            // Given
            UpdateTradeConfigRequest request = new UpdateTradeConfigRequest(500_000L, 3, "limit", true);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(tradeConfigRepository.findByUser(testUser)).willReturn(Optional.of(config));
            given(tradeConfigRepository.save(any(UserTradeConfig.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            TradeConfigResponse response = userService.updateTradeConfig(1L, request);

            // Then
            assertThat(response.getOrderAmount()).isEqualTo(500_000L);
            assertThat(response.getMaxHoldings()).isEqualTo(3);
            assertThat(response.getOrderType()).isEqualTo("limit");
            assertThat(response.getIsActive()).isTrue();

            ArgumentCaptor<UserTradeConfig> captor = ArgumentCaptor.forClass(UserTradeConfig.class);
            then(tradeConfigRepository).should(times(1)).save(captor.capture());
            assertThat(captor.getValue().getIsActive()).isTrue();
        }

        @Test
        @DisplayName("updateTradeConfig - 사용자가 없으면 UserNotFoundException")
        void updateTradeConfig_Fail_UserNotFound() {
            // Given
            UpdateTradeConfigRequest request = new UpdateTradeConfigRequest(500_000L, 3, "limit", true);
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateTradeConfig(1L, request))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining("User not found: 1");

            then(tradeConfigRepository).should(never()).save(any(UserTradeConfig.class));
        }

        @Test
        @DisplayName("updateTradeConfig - 설정이 없으면 ENTITY_NOT_FOUND")
        void updateTradeConfig_Fail_ConfigNotFound() {
            // Given
            UpdateTradeConfigRequest request = new UpdateTradeConfigRequest(500_000L, 3, "limit", true);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(tradeConfigRepository.findByUser(testUser)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateTradeConfig(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("프로필 테스트")
    class ProfileTest {

        @Test
        @DisplayName("getUserProfile - 사용자 정보를 응답 DTO 로 반환한다")
        void getUserProfile_Success() {
            // Given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));

            // When
            UserProfileResponse response = userService.getUserProfile(1L);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getName()).isEqualTo("테스트유저");
            assertThat(response.getPhone()).isEqualTo("010-1234-5678");
        }

        @Test
        @DisplayName("getUserProfile - 사용자가 없으면 UserNotFoundException")
        void getUserProfile_Fail_UserNotFound() {
            // Given
            given(userRepository.findById(99L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.getUserProfile(99L))
                    .isInstanceOf(UserNotFoundException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("updateUserProfile - 이메일이 그대로면 중복 검사 없이 갱신한다")
        void updateUserProfile_Success_SameEmail() {
            // Given
            UpdateUserProfileRequest request = UpdateUserProfileRequest.builder()
                    .name("변경된이름")
                    .email("test@example.com")
                    .phone("010-9999-8888")
                    .birthDate(LocalDate.of(1991, 2, 3))
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserProfileResponse response = userService.updateUserProfile(1L, request);

            // Then
            assertThat(response.getName()).isEqualTo("변경된이름");
            assertThat(response.getPhone()).isEqualTo("010-9999-8888");
            assertThat(response.getBirthDate()).isEqualTo(LocalDate.of(1991, 2, 3));

            then(userRepository).should(never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("updateUserProfile - 새 이메일이 미사용이면 갱신된다")
        void updateUserProfile_Success_NewEmail() {
            // Given
            UpdateUserProfileRequest request = UpdateUserProfileRequest.builder()
                    .name("테스트유저")
                    .email("changed@example.com")
                    .phone("010-1234-5678")
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userRepository.findByEmail("changed@example.com")).willReturn(Optional.empty());
            given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserProfileResponse response = userService.updateUserProfile(1L, request);

            // Then
            assertThat(response.getEmail()).isEqualTo("changed@example.com");
        }

        @Test
        @DisplayName("updateUserProfile - 이미 사용 중인 이메일이면 EMAIL_DUPLICATE")
        void updateUserProfile_Fail_DuplicateEmail() {
            // Given
            UpdateUserProfileRequest request = UpdateUserProfileRequest.builder()
                    .name("테스트유저")
                    .email("taken@example.com")
                    .build();
            User other = User.builder().id(2L).username("other").email("taken@example.com").build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userRepository.findByEmail("taken@example.com")).willReturn(Optional.of(other));

            // When & Then
            assertThatThrownBy(() -> userService.updateUserProfile(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("이미 사용 중인 이메일입니다")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_DUPLICATE);

            then(userRepository).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("updateUserProfile - 사용자가 없으면 UserNotFoundException")
        void updateUserProfile_Fail_UserNotFound() {
            // Given
            UpdateUserProfileRequest request = UpdateUserProfileRequest.builder()
                    .name("이름").email("a@example.com").build();
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateUserProfile(1L, request))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("사용자 설정 테스트")
    class UserSettingsTest {

        @Test
        @DisplayName("getUserSettings - 저장된 JSON 을 JsonNode 로 파싱해 반환한다")
        void getUserSettings_Success() {
            // Given
            UserSettings settings = UserSettings.builder()
                    .id(3L)
                    .userId(1L)
                    .assetOrder("[{\"key\":\"coins\"}]")
                    .darkMode(true)
                    .autoLogin(true)
                    .notifications("{\"stocks\":{\"news\":false}}")
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));

            // When
            UserSettingsResponse response = userService.getUserSettings(1L);

            // Then
            assertThat(response.getDarkMode()).isTrue();
            assertThat(response.getAutoLogin()).isTrue();
            assertThat(response.getAssetOrder().isArray()).isTrue();
            assertThat(response.getAssetOrder().get(0).get("key").asString()).isEqualTo("coins");
            assertThat(response.getNotifications().get("stocks").get("news").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("getUserSettings - JSON 컬럼이 null 이면 빈 배열/빈 객체로 대체한다")
        void getUserSettings_NullJsonColumns() {
            // Given
            UserSettings settings = UserSettings.builder()
                    .id(3L).userId(1L).darkMode(false).autoLogin(false).build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));

            // When
            UserSettingsResponse response = userService.getUserSettings(1L);

            // Then
            assertThat(response.getAssetOrder().isArray()).isTrue();
            assertThat(response.getAssetOrder()).isEmpty();
            assertThat(response.getNotifications().isObject()).isTrue();
            assertThat(response.getNotifications()).isEmpty();
        }

        @Test
        @DisplayName("getUserSettings - 설정이 없으면 기본 설정을 생성해 저장한다")
        void getUserSettings_CreatesDefaults() {
            // Given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(userSettingsRepository.save(any(UserSettings.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserSettingsResponse response = userService.getUserSettings(1L);

            // Then
            ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
            then(userSettingsRepository).should(times(1)).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(1L);
            assertThat(captor.getValue().getDarkMode()).isFalse();
            assertThat(captor.getValue().getAutoLogin()).isFalse();

            assertThat(response.getAssetOrder()).hasSize(4);
            assertThat(response.getNotifications().get("stocks").get("news").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("getUserSettings - 저장된 JSON 이 깨졌으면 INTERNAL_SERVER_ERROR")
        void getUserSettings_Fail_MalformedJson() {
            // Given
            UserSettings settings = UserSettings.builder()
                    .id(3L).userId(1L).assetOrder("{not-json").darkMode(false).autoLogin(false).build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));

            // When & Then
            assertThatThrownBy(() -> userService.getUserSettings(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("설정 정보를 불러오는데 실패했습니다")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("getUserSettings - 사용자가 없으면 UserNotFoundException")
        void getUserSettings_Fail_UserNotFound() {
            // Given
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.getUserSettings(1L))
                    .isInstanceOf(UserNotFoundException.class);

            then(userSettingsRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("updateUserSettings - JsonNode 를 문자열로 직렬화해 저장한다")
        void updateUserSettings_Success() {
            // Given
            UserSettings settings = UserSettings.builder()
                    .id(3L).userId(1L).darkMode(false).autoLogin(false).build();
            UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                    .assetOrder(objectMapper.readTree("[{\"key\":\"bonds\"}]"))
                    .darkMode(true)
                    .autoLogin(true)
                    .notifications(objectMapper.readTree("{\"coins\":{\"trading\":false}}"))
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));
            given(userSettingsRepository.save(any(UserSettings.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserSettingsResponse response = userService.updateUserSettings(1L, request);

            // Then
            ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
            then(userSettingsRepository).should(times(1)).save(captor.capture());
            UserSettings saved = captor.getValue();
            assertThat(saved.getAssetOrder()).isEqualTo("[{\"key\":\"bonds\"}]");
            assertThat(saved.getNotifications()).isEqualTo("{\"coins\":{\"trading\":false}}");
            assertThat(saved.getDarkMode()).isTrue();
            assertThat(saved.getAutoLogin()).isTrue();

            assertThat(response.getDarkMode()).isTrue();
            assertThat(response.getAssetOrder().get(0).get("key").asString()).isEqualTo("bonds");
        }

        @Test
        @DisplayName("updateUserSettings - assetOrder/notifications 가 null 이면 기존 값을 유지한다")
        void updateUserSettings_NullJsonFields_KeepExisting() {
            // Given
            UserSettings settings = UserSettings.builder()
                    .id(3L).userId(1L)
                    .assetOrder("[{\"key\":\"coins\"}]")
                    .notifications("{\"stocks\":{\"news\":true}}")
                    .darkMode(false).autoLogin(false)
                    .build();
            UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                    .darkMode(true).autoLogin(false).build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));
            given(userSettingsRepository.save(any(UserSettings.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserSettingsResponse response = userService.updateUserSettings(1L, request);

            // Then
            ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
            then(userSettingsRepository).should(times(1)).save(captor.capture());
            assertThat(captor.getValue().getAssetOrder()).isEqualTo("[{\"key\":\"coins\"}]");
            assertThat(captor.getValue().getNotifications()).isEqualTo("{\"stocks\":{\"news\":true}}");

            // 응답은 저장된 값이 아니라 요청값(null)을 그대로 되돌려준다.
            assertThat(response.getAssetOrder()).isNull();
            assertThat(response.getNotifications()).isNull();
        }

        @Test
        @DisplayName("updateUserSettings - 설정이 없으면 기본 설정을 만든 뒤 갱신한다")
        void updateUserSettings_CreatesDefaultsFirst() {
            // Given
            UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                    .darkMode(true).autoLogin(true).build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(userSettingsRepository.save(any(UserSettings.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            UserSettingsResponse response = userService.updateUserSettings(1L, request);

            // Then - 기본 설정 저장 1회 + 갱신 저장 1회
            then(userSettingsRepository).should(times(2)).save(any(UserSettings.class));
            assertThat(response.getDarkMode()).isTrue();
        }

        @Test
        @DisplayName("updateUserSettings - 사용자가 없으면 UserNotFoundException")
        void updateUserSettings_Fail_UserNotFound() {
            // Given
            UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                    .darkMode(true).autoLogin(true).build();
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateUserSettings(1L, request))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("회원 탈퇴 테스트")
    class DeleteAccountTest {

        @Test
        @DisplayName("deleteAccount - 연관 데이터를 모두 지운 뒤 사용자를 삭제한다")
        void deleteAccount_Success() {
            // Given
            UserKisAccount kisAccount = UserKisAccount.builder().id(10L).user(testUser).build();
            UserTradeConfig config = UserTradeConfig.builder().id(5L).user(testUser).build();
            UserSettings settings = UserSettings.builder().id(3L).userId(1L).build();

            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            given(tradeConfigRepository.findByUserId(1L)).willReturn(Optional.of(config));
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.of(settings));

            // When
            userService.deleteAccount(1L);

            // Then
            then(refreshTokenRepository).should(times(1)).deleteByUserId(1L);
            then(kisAccountRepository).should(times(1)).delete(kisAccount);
            then(tradeConfigRepository).should(times(1)).delete(config);
            then(userSettingsRepository).should(times(1)).delete(settings);
            then(userRepository).should(times(1)).delete(testUser);
        }

        @Test
        @DisplayName("deleteAccount - 연관 데이터가 없어도 사용자는 삭제된다")
        void deleteAccount_Success_NoRelatedEntities() {
            // Given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(tradeConfigRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(userSettingsRepository.findByUserId(1L)).willReturn(Optional.empty());

            // When
            userService.deleteAccount(1L);

            // Then
            then(kisAccountRepository).should(never()).delete(any(UserKisAccount.class));
            then(tradeConfigRepository).should(never()).delete(any(UserTradeConfig.class));
            then(userSettingsRepository).should(never()).delete(any(UserSettings.class));
            then(userRepository).should(times(1)).delete(testUser);
        }

        @Test
        @DisplayName("deleteAccount - 사용자가 없으면 UserNotFoundException 이고 아무 것도 지우지 않는다")
        void deleteAccount_Fail_UserNotFound() {
            // Given
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.deleteAccount(1L))
                    .isInstanceOf(UserNotFoundException.class);

            then(refreshTokenRepository).should(never()).deleteByUserId(any());
            then(userRepository).should(never()).delete(any(User.class));
        }
    }

    @Nested
    @DisplayName("KIS 계좌 테스트")
    class KisAccountTest {

        private UserKisAccount kisAccount;

        @BeforeEach
        void setUp() {
            kisAccount = UserKisAccount.builder()
                    .id(10L)
                    .user(testUser)
                    .accountNumber("12345678-01")
                    .accountProductCode("01")
                    .appKey("ENC_APP_KEY")
                    .appSecret("ENC_APP_SECRET")
                    .htsId("HTSID")
                    .isVerified(true)
                    .build();
        }

        @Test
        @DisplayName("getKisAccount - 저장된 자격증명을 복호화해 반환한다")
        void getKisAccount_Success() {
            // Given
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            given(jasyptStringEncryptor.decrypt("ENC_APP_KEY")).willReturn("plain-app-key");
            given(jasyptStringEncryptor.decrypt("ENC_APP_SECRET")).willReturn("plain-app-secret");

            // When
            KisAccountResponse response = userService.getKisAccount(1L);

            // Then
            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getAccountNumber()).isEqualTo("12345678-01");
            assertThat(response.getAppKey()).isEqualTo("plain-app-key");
            assertThat(response.getAppSecret()).isEqualTo("plain-app-secret");
            assertThat(response.getIsVerified()).isTrue();
        }

        @Test
        @DisplayName("getKisAccount - 복호화 실패(레거시 평문)는 예외 대신 null 로 degrade")
        void getKisAccount_DecryptFailure_ReturnsNullFields() {
            // Given
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            willThrow(new RuntimeException("decrypt failed"))
                    .given(jasyptStringEncryptor).decrypt(anyString());

            // When
            KisAccountResponse response = userService.getKisAccount(1L);

            // Then
            assertThat(response.getAppKey()).isNull();
            assertThat(response.getAppSecret()).isNull();
            assertThat(response.getAccountNumber()).isEqualTo("12345678-01");
        }

        @Test
        @DisplayName("getKisAccount - 계좌가 없으면 ENTITY_NOT_FOUND")
        void getKisAccount_Fail_NotFound() {
            // Given
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.getKisAccount(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("KIS 계좌 정보를 찾을 수 없습니다")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
        }

        @Test
        @DisplayName("updateKisAccount - 자격증명은 Jasypt 로 암호화 저장하고 검증 상태를 초기화한다")
        void updateKisAccount_Success_EncryptsAndResetsVerification() {
            // Given
            UpdateKisAccountRequest request = UpdateKisAccountRequest.builder()
                    .accountNumber("12345678-01")
                    .appKey("new-app-key")
                    .appSecret("new-app-secret")
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            given(jasyptStringEncryptor.encrypt("new-app-key")).willReturn("ENC_NEW_KEY");
            given(jasyptStringEncryptor.encrypt("new-app-secret")).willReturn("ENC_NEW_SECRET");
            given(jasyptStringEncryptor.decrypt("ENC_NEW_KEY")).willReturn("new-app-key");
            given(jasyptStringEncryptor.decrypt("ENC_NEW_SECRET")).willReturn("new-app-secret");
            given(kisAccountRepository.save(any(UserKisAccount.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            KisAccountResponse response = userService.updateKisAccount(1L, request);

            // Then - 평문이 그대로 저장되면 안 된다.
            ArgumentCaptor<UserKisAccount> captor = ArgumentCaptor.forClass(UserKisAccount.class);
            then(kisAccountRepository).should(times(1)).save(captor.capture());
            UserKisAccount saved = captor.getValue();
            assertThat(saved.getAppKey()).isEqualTo("ENC_NEW_KEY");
            assertThat(saved.getAppSecret()).isEqualTo("ENC_NEW_SECRET");
            assertThat(saved.getIsVerified()).isFalse();
            // 미제공 필드는 기존 값 유지
            assertThat(saved.getHtsId()).isEqualTo("HTSID");

            assertThat(response.getIsVerified()).isFalse();
            assertThat(response.getAppKey()).isEqualTo("new-app-key");
        }

        @Test
        @DisplayName("updateKisAccount - 다른 사용자가 쓰는 계좌번호면 KIS_ACCOUNT_DUPLICATE")
        void updateKisAccount_Fail_DuplicateAccountNumber() {
            // Given
            UpdateKisAccountRequest request = UpdateKisAccountRequest.builder()
                    .accountNumber("99999999-01")
                    .appKey("k").appSecret("s")
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            given(kisAccountRepository.findByAccountNumber("99999999-01"))
                    .willReturn(Optional.of(UserKisAccount.builder().id(20L).build()));

            // When & Then
            assertThatThrownBy(() -> userService.updateKisAccount(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("이미 등록된 계좌번호입니다")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KIS_ACCOUNT_DUPLICATE);

            then(kisAccountRepository).should(never()).save(any(UserKisAccount.class));
        }

        @Test
        @DisplayName("updateKisAccount - 미사용 계좌번호로는 변경할 수 있다")
        void updateKisAccount_Success_NewAccountNumber() {
            // Given
            UpdateKisAccountRequest request = UpdateKisAccountRequest.builder()
                    .accountNumber("99999999-01")
                    .appKey("k").appSecret("s")
                    .build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.of(kisAccount));
            given(kisAccountRepository.findByAccountNumber("99999999-01")).willReturn(Optional.empty());
            given(jasyptStringEncryptor.encrypt(anyString())).willReturn("ENC");
            given(jasyptStringEncryptor.decrypt("ENC")).willReturn("plain");
            given(kisAccountRepository.save(any(UserKisAccount.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            KisAccountResponse response = userService.updateKisAccount(1L, request);

            // Then
            assertThat(response.getAccountNumber()).isEqualTo("99999999-01");
        }

        @Test
        @DisplayName("updateKisAccount - 사용자가 없으면 UserNotFoundException")
        void updateKisAccount_Fail_UserNotFound() {
            // Given
            UpdateKisAccountRequest request = UpdateKisAccountRequest.builder()
                    .accountNumber("12345678-01").appKey("k").appSecret("s").build();
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateKisAccount(1L, request))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("updateKisAccount - KIS 계좌가 없으면 ENTITY_NOT_FOUND")
        void updateKisAccount_Fail_KisAccountNotFound() {
            // Given
            UpdateKisAccountRequest request = UpdateKisAccountRequest.builder()
                    .accountNumber("12345678-01").appKey("k").appSecret("s").build();
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(kisAccountRepository.findByUserId(1L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.updateKisAccount(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
        }
    }
}
