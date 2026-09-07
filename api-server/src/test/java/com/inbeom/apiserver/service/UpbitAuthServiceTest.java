package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.UserUpbitAccount;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.UserUpbitAccountRepository;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 저장된 업비트 자격증명을 꺼내 쓰는 경로 테스트.
 *
 * <p>{@link UserService} 쪽 저장 시점 검증은 {@code UpbitAccountSecurityTest} 가 고정한다. 이 파일은
 * <b>사용 시점</b>을 맡는다 — 두 지점을 모두 막는 이유가 서로 다르기 때문이다. 저장 시점만 막으면
 * 그 검사가 생기기 전에 들어온 짧은 키가 DB 에 그대로 남아 <b>주문 경로에서 500 으로 터지고</b>,
 * 사용 시점만 막으면 사용자가 주문을 넣어 볼 때까지 키가 잘못됐다는 사실을 모른다.
 *
 * <p>2차 QA 의 뮤테이션 테스트에서 {@code UpbitAuthService} 쪽 가드를 지워도 전체 테스트가 통과해
 * (= 아무도 고정하지 않는 상태) 이 파일을 추가했다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UpbitAuthService — 저장된 자격증명 사용")
class UpbitAuthServiceTest {

    /** 실제 업비트 Secret Key 와 같은 40자. HS256 하한(32바이트)을 넘는다. */
    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef01234567";

    @Mock private UserUpbitAccountRepository upbitAccountRepository;
    @Mock private StringEncryptor jasyptStringEncryptor;

    @InjectMocks private UpbitAuthService upbitAuthService;

    private void storedAccount(String encAccess, String encSecret) {
        given(upbitAccountRepository.findByUserId(1L)).willReturn(Optional.of(
                UserUpbitAccount.builder().id(10L).accessKey(encAccess).secretKey(encSecret).build()));
    }

    @Test
    @DisplayName("정상 키는 복호화해서 그대로 돌려준다")
    void returnsDecryptedCredentials() {
        storedAccount("ENC(access)", "ENC(secret)");
        given(jasyptStringEncryptor.decrypt("ENC(access)")).willReturn("real-access-key");
        given(jasyptStringEncryptor.decrypt("ENC(secret)")).willReturn(VALID_SECRET);

        UpbitAuthService.UpbitCredentials credentials = upbitAuthService.getCredentials(1L);

        assertThat(credentials.accessKey()).isEqualTo("real-access-key");
        assertThat(credentials.secretKey()).isEqualTo(VALID_SECRET);
    }

    @Test
    @DisplayName("저장돼 있던 32바이트 미만 Secret Key 는 6006 으로 끊는다")
    void tooShortStoredSecretKeyIsRejected() {
        // 길이 하한이 생기기 전에 저장된 행이 남아 있을 수 있다. 잡지 않으면 jjwt 가
        // WeakKeyException(RuntimeException) 을 던져 주문·자산 조회가 500 이 된다 —
        // BusinessException 이 아니라서 6000번대로 정규화되지도 않는다.
        storedAccount("ENC(access)", "ENC(short)");
        given(jasyptStringEncryptor.decrypt("ENC(access)")).willReturn("real-access-key");
        given(jasyptStringEncryptor.decrypt("ENC(short)")).willReturn("too-short");

        assertThatThrownBy(() -> upbitAuthService.getCredentials(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPBIT_SECRET_KEY_TOO_SHORT);
    }

    @Test
    @DisplayName("경계값 — 정확히 32바이트는 통과한다")
    void exactlyMinimumLengthIsAccepted() {
        String thirtyTwo = "0123456789abcdef0123456789abcdef";
        assertThat(thirtyTwo).hasSize(32);
        storedAccount("ENC(access)", "ENC(secret)");
        given(jasyptStringEncryptor.decrypt("ENC(access)")).willReturn("real-access-key");
        given(jasyptStringEncryptor.decrypt("ENC(secret)")).willReturn(thirtyTwo);

        assertThat(upbitAuthService.getCredentials(1L).secretKey()).isEqualTo(thirtyTwo);
    }

    @Test
    @DisplayName("계좌 미등록은 6000")
    void missingAccountIsNotFound() {
        given(upbitAccountRepository.findByUserId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> upbitAuthService.getCredentials(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPBIT_ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("복호화 실패는 평문 폴백 없이 6004 로 끊는다")
    void decryptionFailureDoesNotFallBackToPlaintext() {
        // 폴백은 "암호화가 아예 적용되지 않았다"는 상태를 정상 동작으로 위장해,
        // 평문 Secret Key 가 그대로 업비트로 나가는 것을 아무도 눈치채지 못하게 만든다.
        storedAccount("ENC(access)", "ENC(secret)");
        given(jasyptStringEncryptor.decrypt("ENC(secret)"))
                .willThrow(new RuntimeException("wrong JASYPT_PASSWORD"));

        assertThatThrownBy(() -> upbitAuthService.getCredentials(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPBIT_CREDENTIAL_DECRYPT_FAILED);
    }
}
