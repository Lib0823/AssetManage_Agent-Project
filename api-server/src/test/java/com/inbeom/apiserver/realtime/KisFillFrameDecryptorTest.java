package com.inbeom.apiserver.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KisFillFrameDecryptor} AES/CBC/PKCS5 known-vector 단위 테스트.
 *
 * <p>체결통보 e2e 는 실제 주문 체결(장중 실/모의 계좌) 시에만 프레임이 발생하므로 라이브 검증이
 * 어렵다. 따라서 <b>복호 로직의 정확성(transformation/charset/패딩)을 오프라인에서 고정 벡터로
 * 검증</b>하는 것이 핵심 안전망이다.
 *
 * <p>벡터: key(32바이트 UTF-8)=AES-256, iv(16바이트), 평문=체결통보 ^-구분 샘플.
 * {@link #KNOWN_CIPHERTEXT_B64} 는 동일 알고리즘으로 사전 계산한 base64 암호문(스펙 드리프트 감지용).
 */
@DisplayName("KisFillFrameDecryptor AES known-vector 단위 테스트")
class KisFillFrameDecryptorTest {

    private final KisFillFrameDecryptor decryptor = new KisFillFrameDecryptor();

    /** AES-256 키(UTF-8 32바이트). KIS 체결통보 key 는 통상 문자열 그대로 바이트로 쓴다. */
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    /** iv(UTF-8 16바이트). */
    private static final String IV = "abcdef9876543210";
    /** 체결통보 ^-구분 평문 샘플 (인덱스: [0]종목 ... [4]SELN_BYOV_CLS ... [12]CNTG_YN). */
    private static final String PLAINTEXT =
            "005930^0000123456^^^02^^^^005930^10^70100^093015^2^Y";
    /** 위 KEY/IV/PLAINTEXT 를 AES/CBC/PKCS5 로 암호화한 base64 (사전 계산, 고정 벡터). */
    private static final String KNOWN_CIPHERTEXT_B64 =
            "p/EPJ6AlO6VjtPdZxZgHTRffmknEAkZRoa3xHPr2C9O6AHFkK5WBnA5TtplHSFJXH9yEUIX82y0PQh1Xy6Gnhg==";

    @Test
    @DisplayName("고정 base64 벡터를 복호하면 원본 평문이 나온다 (스펙/charset 드리프트 감지)")
    void decryptsKnownVector() {
        String decrypted = decryptor.decrypt(KEY, IV, KNOWN_CIPHERTEXT_B64);
        assertThat(decrypted).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("동일 알고리즘으로 암호화→복호화 라운드트립이 원본을 복원한다")
    void roundTrip() throws Exception {
        String ciphertext = encrypt(KEY, IV, PLAINTEXT);
        // 사전 계산 벡터와 즉석 암호화 결과가 일치(결정적 CBC).
        assertThat(ciphertext).isEqualTo(KNOWN_CIPHERTEXT_B64);

        String decrypted = decryptor.decrypt(KEY, IV, ciphertext);
        assertThat(decrypted).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("키/iv/암호문 누락 시 예외 없이 null 반환 (graceful degrade)")
    void returnsNullOnMissingInput() {
        assertThat(decryptor.decrypt(null, IV, KNOWN_CIPHERTEXT_B64)).isNull();
        assertThat(decryptor.decrypt(KEY, null, KNOWN_CIPHERTEXT_B64)).isNull();
        assertThat(decryptor.decrypt(KEY, IV, null)).isNull();
        assertThat(decryptor.decrypt("", IV, KNOWN_CIPHERTEXT_B64)).isNull();
        assertThat(decryptor.decrypt(KEY, IV, "  ")).isNull();
    }

    @Test
    @DisplayName("잘못된 키로 복호하면 예외를 던지지 않고 null 반환")
    void returnsNullOnWrongKey() {
        String wrongKey = "ffffffffffffffffffffffffffffffff"; // 동일 길이, 다른 값
        String result = decryptor.decrypt(wrongKey, IV, KNOWN_CIPHERTEXT_B64);
        // 패딩 불일치 → 예외 삼키고 null (또는 깨진 문자열이 아닌 null 보장).
        assertThat(result).isNull();
    }

    /** 테스트용 암호화 (프로덕션 디크립터와 대칭 검증). */
    private static String encrypt(String key, String iv, String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ct);
    }
}
