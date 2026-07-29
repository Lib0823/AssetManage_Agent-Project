package com.inbeom.apiserver.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * KIS 체결통보(flag 1) 암호 프레임 복호기.
 *
 * <p>KIS 실시간 체결통보(H0STCNI0/H0STCNI9)는 구독 ACK 의 {@code body.output.{key,iv}} 로
 * 전달되는 대칭키로 데이터 프레임을 암호화해 보낸다. 알고리즘은 KIS 명세상
 * <b>AES/CBC/PKCS5Padding</b> 이며, key/iv 는 <b>ASCII/UTF-8 문자열의 바이트</b> 그대로
 * (AES-128/192/256 은 key 문자열 길이로 결정 — KIS 는 통상 32바이트 = AES-256),
 * ciphertext 는 <b>Base64</b> 인코딩 문자열이다.
 *
 * <p><b>보안</b>: key/iv 는 절대 로그로 남기지 않는다. 복호 실패 시에도 키 값을 노출하지 않고
 * 길이/예외 메시지만 디버그 로깅한다.
 *
 * <p>오프라인 핵심 검증: {@code KisFillFrameDecryptorTest} 의 AES known-vector 라운드트립.
 * 신규 의존성 없이 {@code javax.crypto} 만 사용한다.
 */
@Slf4j
@Component
public class KisFillFrameDecryptor {

    /** MUST-VERIFY(명세 확정): KIS 체결통보 프레임 암호 스펙. 한 곳에만 둔다. */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String ALGORITHM = "AES";

    /**
     * Base64 ciphertext 를 key/iv(UTF-8 bytes)로 AES/CBC/PKCS5 복호한다.
     *
     * @param key            구독 ACK 의 output.key (대칭키 문자열)
     * @param iv             구독 ACK 의 output.iv (초기화벡터 문자열)
     * @param base64CipherText flag 1 데이터(base64 암호문)
     * @return 복호된 평문(UTF-8); 입력 누락/복호 실패 시 null (절대 키 미노출)
     */
    public String decrypt(String key, String iv, String base64CipherText) {
        if (key == null || key.isBlank()
                || iv == null || iv.isBlank()
                || base64CipherText == null || base64CipherText.isBlank()) {
            log.debug("[fills] decrypt skipped: missing key/iv/ciphertext (keyLen={}, ivLen={})",
                    key == null ? 0 : key.length(), iv == null ? 0 : iv.length());
            return null;
        }
        try {
            SecretKeySpec keySpec =
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            IvParameterSpec ivSpec =
                    new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] cipherBytes = Base64.getDecoder().decode(base64CipherText.trim());
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 키/iv 값은 로깅하지 않는다 — 길이와 예외 메시지만.
            log.warn("[fills] AES decrypt failed: {} (keyLen={}, ivLen={})",
                    e.getMessage(), key.length(), iv.length());
            return null;
        }
    }
}
