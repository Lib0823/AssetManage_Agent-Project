package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.UpbitApiClient;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserUpbitAccount;
import com.inbeom.apiserver.dto.user.UpbitAccountResponse;
import com.inbeom.apiserver.dto.user.UpdateUpbitAccountRequest;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.CoinTradeHistoryRepository;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserSettingsRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import com.inbeom.apiserver.repository.UserUpbitAccountRepository;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 업비트 자격증명 노출 방지 테스트.
 *
 * <p>KIS 쪽은 {@code decryptForDisplay} 로 AppSecret 평문을 응답에 싣는다. 그건 실수가 아니라,
 * 암호화 도입 이전의 평문 레코드 때문에 프로필 화면이 죽으면 사용자가 키를 재등록할 방법조차
 * 없어지는 상황을 피하려는 <b>의도된 트레이드오프</b>다.
 *
 * <p>{@code user_upbit_accounts} 는 신규 테이블이라 그 사정이 없다. 그래서 같은 패턴을 물려받지
 * 않고 Secret Key 를 아예 응답에 담지 않는데 — <b>나중에 "KIS 처럼 맞추자"며 되돌리기 쉬운
 * 종류의 결정</b>이라 테스트로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("업비트 자격증명 노출 방지")
class UpbitAccountSecurityTest {

    /** 실제 업비트 Secret Key 와 같은 40자. HS256 하한(32바이트)을 넘는다. */
    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef01234567";

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
    private UserUpbitAccountRepository upbitAccountRepository;
    @Mock
    private CoinTradeHistoryRepository coinTradeHistoryRepository;
    @Mock
    private UpbitAuthService upbitAuthService;
    @Mock
    private UpbitApiClient upbitApiClient;
    @Mock
    private StringEncryptor jasyptStringEncryptor;

    private UserService userService;
    private UserUpbitAccount storedAccount;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                tradeConfigRepository,
                kisAccountRepository,
                refreshTokenRepository,
                userSettingsRepository,
                upbitAccountRepository,
                coinTradeHistoryRepository,
                upbitAuthService,
                upbitApiClient,
                new ObjectMapper(),
                jasyptStringEncryptor);

        storedAccount = UserUpbitAccount.builder()
                .id(10L)
                .user(User.builder().id(1L).build())
                .accessKey("ENC(existing-access)")
                .secretKey("ENC(existing-secret)")
                .isVerified(true)
                .build();

        lenient().when(userRepository.findById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).build()));
        lenient().when(upbitAccountRepository.findByUserId(1L))
                .thenReturn(Optional.of(storedAccount));
        lenient().when(upbitAccountRepository.save(any(UserUpbitAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("응답 DTO에 Secret Key 를 담을 필드 자체가 없다")
    void responseDtoHasNoSecretKeyField() {
        // 값을 null 로 두는 것과 필드가 없는 것은 다르다. 필드가 있으면 누군가 채운다.
        var fieldNames = Arrays.stream(UpbitAccountResponse.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames)
                .as("Secret Key 는 마스킹조차 하지 말고 등록 여부(boolean)만 노출한다")
                .doesNotContain("secretKey", "secret", "apiSecret");

        assertThat(fieldNames)
                .as("등록 여부와 Access Key 마스킹만 노출한다")
                .contains("secretKeyRegistered", "accessKeyMasked");
    }

    @Test
    @DisplayName("응답 DTO에 Access Key 원문을 담을 필드도 없다")
    void responseDtoHasNoRawAccessKeyField() {
        var fieldNames = Arrays.stream(UpbitAccountResponse.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames).doesNotContain("accessKey");
    }

    @Test
    @DisplayName("빈 Secret Key 로 수정하면 기존 키를 지우지 않는다")
    void blankSecretKeyKeepsExistingCredential() {
        // 조회 응답이 실제 키를 돌려주지 않으므로 프론트는 입력칸을 되채울 수 없다.
        // 따라서 빈 값은 "지워라"가 아니라 "그대로 둬라"여야 한다 —
        // 반대로 해석하면 사용자가 닉네임만 바꿔도 거래가 끊긴다.
        UpdateUpbitAccountRequest request = new UpdateUpbitAccountRequest();
        request.setAccessKey("  ");
        request.setSecretKey("");

        userService.updateUpbitAccount(1L, request);

        assertThat(storedAccount.getAccessKey())
                .as("공백만 있는 입력은 '변경 없음'이다")
                .isEqualTo("ENC(existing-access)");
        assertThat(storedAccount.getSecretKey()).isEqualTo("ENC(existing-secret)");
        assertThat(storedAccount.getIsVerified())
                .as("바뀐 값이 없으므로 이전 검증 결과를 무효화할 이유도 없다")
                .isTrue();
        verify(upbitApiClient, never())
                .getAuthenticated(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Access Key 만 바꾸면 Secret Key 는 그대로 남는다")
    void updatingOnlyAccessKeyKeepsSecret() {
        given(jasyptStringEncryptor.encrypt("new-access")).willReturn("ENC(new-access)");

        UpdateUpbitAccountRequest request = new UpdateUpbitAccountRequest();
        request.setAccessKey("new-access");
        request.setSecretKey("");

        userService.updateUpbitAccount(1L, request);

        assertThat(storedAccount.getAccessKey()).isEqualTo("ENC(new-access)");
        assertThat(storedAccount.getSecretKey())
                .as("Secret Key 를 되채울 수 없는 화면이므로 건드리면 안 된다")
                .isEqualTo("ENC(existing-secret)");
    }

    @Test
    @DisplayName("32바이트 미만 Secret Key 는 저장 단계에서 거부한다")
    void tooShortSecretKeyIsRejectedBeforeStorage() {
        // 잡지 않으면 jjwt 가 WeakKeyException(RuntimeException)을 던져 주문 경로가 500 이 된다.
        UpdateUpbitAccountRequest request = new UpdateUpbitAccountRequest();
        request.setSecretKey("too-short");

        assertThatThrownBy(() -> userService.updateUpbitAccount(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPBIT_SECRET_KEY_TOO_SHORT);

        assertThat(storedAccount.getSecretKey())
                .as("거부된 값이 기존 키를 덮어쓰면 안 된다")
                .isEqualTo("ENC(existing-secret)");
    }

    @Test
    @DisplayName("40자 Secret Key 는 정상 통과한다")
    void realLengthSecretKeyIsAccepted() {
        given(jasyptStringEncryptor.encrypt(VALID_SECRET)).willReturn("ENC(new-secret)");

        UpdateUpbitAccountRequest request = new UpdateUpbitAccountRequest();
        request.setSecretKey(VALID_SECRET);

        UpbitAccountResponse response = userService.updateUpbitAccount(1L, request);

        assertThat(storedAccount.getSecretKey()).isEqualTo("ENC(new-secret)");
        assertThat(response.isSecretKeyRegistered()).isTrue();
    }
}
