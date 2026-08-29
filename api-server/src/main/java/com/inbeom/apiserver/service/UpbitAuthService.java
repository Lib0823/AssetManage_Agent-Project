package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.UserUpbitAccount;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.UserUpbitAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Service;

/**
 * 저장된 업비트 자격증명 조회 + 복호화.
 *
 * <p>{@link KisAuthService} 와 달리 토큰 캐시가 없다 — 업비트는 요청마다 JWT 를 새로 서명하므로
 * 캐시할 토큰이라는 개념 자체가 없다. 이 클래스가 하는 일은 "DB 에서 꺼내 복호화"뿐이다.
 *
 * <p><b>복호화 실패 시 평문으로 폴백하지 않는다</b>({@code KisAuthService:141-155} 와 같은 판단).
 * 폴백은 "암호화가 아예 적용되지 않았다"는 상태를 정상 동작으로 위장해, 평문 Secret Key 가 그대로
 * 업비트로 나가는 것을 아무도 눈치채지 못하게 만든다. 재등록이 필요한 상태이므로 6004 로 끊는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpbitAuthService {

    /**
     * HS256 서명에 필요한 최소 Secret Key 길이(바이트). jjwt {@code Keys.hmacShaKeyFor} 의 요구치다.
     *
     * <p>실제 업비트 Secret Key 는 40자라 정상 등록에서는 걸리지 않는다. 오타·잘못된 붙여넣기로
     * 짧은 값이 들어왔을 때 <b>주문 경로에서 500 이 나는 대신</b> 여기서 400 으로 끊기 위한 하한이다.
     */
    static final int MIN_SECRET_KEY_BYTES = 32;

    private final UserUpbitAccountRepository upbitAccountRepository;
    private final StringEncryptor jasyptStringEncryptor;

    /**
     * 사용자별 업비트 키를 복호화해 돌려준다.
     *
     * @throws BusinessException 계좌 미등록(6000) 또는 복호화 실패(6004)
     */
    public UpbitCredentials getCredentials(Long userId) {
        UserUpbitAccount account = upbitAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UPBIT_ACCOUNT_NOT_FOUND,
                        "업비트 계좌가 등록되어 있지 않습니다. 설정에서 API 키를 등록해 주세요."));

        String secretKey = decryptCredential(account.getSecretKey(), "secret_key", userId);
        // 저장 시점에도 검사하지만, 이 하한이 생기기 전에 저장된 행이 남아 있을 수 있다.
        requireSignableSecretKey(secretKey);

        return new UpbitCredentials(
                decryptCredential(account.getAccessKey(), "access_key", userId),
                secretKey);
    }

    /**
     * Secret Key 가 HS256 서명에 쓸 수 있는 길이인지 확인한다.
     *
     * <p>저장 시점({@code UserService.updateUpbitAccount})과 사용 시점({@link #getCredentials}) 양쪽에서
     * 부른다. 저장 시점만 막으면 이 검사가 생기기 전에 들어온 짧은 키가 그대로 남아 주문에서 터지고,
     * 사용 시점만 막으면 사용자가 <b>주문을 넣어 볼 때까지</b> 키가 잘못됐다는 사실을 모른다.
     *
     * @throws BusinessException 32바이트 미만이면 6006
     */
    public static void requireSignableSecretKey(String secretKey) {
        if (secretKey == null || secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                < MIN_SECRET_KEY_BYTES) {
            throw new BusinessException(ErrorCode.UPBIT_SECRET_KEY_TOO_SHORT,
                    "업비트 Secret Key 가 너무 짧습니다(" + MIN_SECRET_KEY_BYTES + "자 이상). "
                            + "업비트에서 발급받은 키를 다시 확인해 주세요.");
        }
    }

    /** 등록 여부만 확인한다(복호화하지 않는다). */
    public boolean hasAccount(Long userId) {
        return upbitAccountRepository.findByUserId(userId).isPresent();
    }

    private String decryptCredential(String encrypted, String fieldName, Long userId) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new BusinessException(ErrorCode.UPBIT_CREDENTIAL_DECRYPT_FAILED,
                    "업비트 자격증명(" + fieldName + ")이 비어 있습니다. 키를 다시 등록해 주세요.");
        }
        try {
            return jasyptStringEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.error("Jasypt decryption failed for upbit credentials userId={} field={}. "
                            + "JASYPT_PASSWORD 가 저장 시점과 다르거나 값이 평문으로 저장되어 있습니다.",
                    userId, fieldName);
            throw new BusinessException(ErrorCode.UPBIT_CREDENTIAL_DECRYPT_FAILED,
                    "저장된 업비트 자격증명을 복호화할 수 없습니다. 설정에서 API 키를 다시 등록해 주세요.", e);
        }
    }

    /**
     * 복호화된 업비트 자격증명.
     *
     * <p>이 값은 {@code UpbitApiClient.buildJwt} 로만 흘러가며, <b>어떤 응답 DTO 에도 담기지 않는다.</b>
     */
    public record UpbitCredentials(String accessKey, String secretKey) {
    }
}
