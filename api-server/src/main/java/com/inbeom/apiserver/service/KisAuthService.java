package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.kis.KisTokenResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisAuthService {

    private final UserKisAccountRepository kisAccountRepository;
    private final StringEncryptor jasyptStringEncryptor;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${kis.base-url}")
    private String kisBaseUrl;

    @Value("${kis.token-cache-ttl}")
    private long tokenCacheTtl;

    // KIS Access Token Cache: kis_account_id -> TokenCache
    private final Map<Long, KisTokenCache> userKisTokens = new ConcurrentHashMap<>();

    /**
     * Get KIS Access Token (with caching)
     * 1st call: DB query + decrypt + KIS OAuth (~500ms)
     * Subsequent calls: Cache hit (~50ms, 99% case)
     */
    public String getKisAccessToken(Long kisAccountId) {
        // 1. Check cache
        KisTokenCache cached = userKisTokens.get(kisAccountId);
        if (cached != null && !cached.isExpired()) {
            log.debug("KIS token cache hit for kis_account_id={}", kisAccountId);
            return cached.getAccessToken();
        }

        log.debug("KIS token cache miss for kis_account_id={}, fetching from DB and KIS API", kisAccountId);

        // 2. DB query + decrypt (only on cache miss)
        UserKisAccount kisAccount = kisAccountRepository.findById(kisAccountId)
                .orElseThrow(() -> new KisAccountNotFoundException(kisAccountId));

        String appKey = decryptCredential(kisAccount.getAppKey(), "app_key", kisAccountId);
        String appSecret = decryptCredential(kisAccount.getAppSecret(), "app_secret", kisAccountId);

        // 3. Issue KIS OAuth token
        String kisToken = requestKisOAuthToken(appKey, appSecret);

        // 4. Cache for 24h
        userKisTokens.put(kisAccountId, new KisTokenCache(kisToken, tokenCacheTtl));
        log.info("KIS token cached for kis_account_id={}, expires in {}ms", kisAccountId, tokenCacheTtl);

        return kisToken;
    }

    /**
     * Request KIS OAuth2 Token.
     */
    private String requestKisOAuthToken(String appKey, String appSecret) {
        String url = kisBaseUrl + "/oauth2/tokenP";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // KIS API requires JSON format, not form-data
        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("appsecret", appSecret);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<KisTokenResponse> response = restTemplate.postForEntity(url, request, KisTokenResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody().getAccessToken();
            } else {
                throw KisApiException.oauthFailed("Failed to get KIS OAuth token: " + response.getStatusCode());
            }
        } catch (HttpClientErrorException e) {
            // 4xx: Invalid credentials or parameters
            log.error("KIS API client error (4xx): {}", e.getStatusCode());
            throw KisApiException.clientError("Invalid KIS credentials or parameters", e);
        } catch (HttpServerErrorException e) {
            // 5xx: KIS server error
            log.error("KIS API server error (5xx): {}", e.getStatusCode());
            throw KisApiException.serverError("KIS server error, please try again later", e);
        } catch (ResourceAccessException e) {
            // Network error
            log.error("KIS API network error: {}", e.getMessage());
            throw KisApiException.networkError("KIS API unreachable", e);
        } catch (Exception e) {
            // Other errors
            log.error("Unexpected error requesting KIS OAuth token", e);
            throw KisApiException.oauthFailed("KIS OAuth request failed", e);
        }
    }

    /**
     * Get decrypted AppKey and AppSecret for KIS API calls
     */
    public KisCredentials getKisCredentials(Long kisAccountId) {
        UserKisAccount kisAccount = kisAccountRepository.findById(kisAccountId)
                .orElseThrow(() -> new KisAccountNotFoundException(kisAccountId));

        String appKey = decryptCredential(kisAccount.getAppKey(), "app_key", kisAccountId);
        String appSecret = decryptCredential(kisAccount.getAppSecret(), "app_secret", kisAccountId);

        return new KisCredentials(appKey, appSecret, kisAccount.getAccountNumber(),
                kisAccount.getAccountProductCode(), kisBaseUrl);
    }

    /**
     * 저장된 KIS 자격증명을 복호화한다.
     *
     * <p>복호화 실패 시 평문으로 폴백하지 않는다. 폴백은 "암호화가 아예 적용되지 않았다"는 상태를
     * 정상 동작으로 위장해, 평문 app_key 가 그대로 KIS 로 나가는 것을 아무도 눈치채지 못하게 만든다.
     * 실패는 재등록이 필요한 상태이므로 {@link ErrorCode#KIS_CREDENTIAL_DECRYPT_FAILED}(4006)로 끊는다.
     */
    private String decryptCredential(String encrypted, String fieldName, Long kisAccountId) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new BusinessException(ErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED,
                    "KIS 자격증명(" + fieldName + ")이 비어 있습니다. 계좌를 다시 등록해 주세요.");
        }
        try {
            return jasyptStringEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.error("Jasypt decryption failed for kis_account_id={} field={}. "
                    + "JASYPT_PASSWORD 가 저장 시점과 다르거나 값이 평문으로 저장되어 있습니다.",
                    kisAccountId, fieldName);
            throw new BusinessException(ErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED,
                    "저장된 KIS 자격증명을 복호화할 수 없습니다. 프로필에서 KIS 계좌를 다시 등록해 주세요.", e);
        }
    }

    /**
     * KIS Token Cache Entry
     */
    private static class KisTokenCache {
        private final String accessToken;
        private final LocalDateTime expiryTime;

        public KisTokenCache(String accessToken, long ttlMillis) {
            this.accessToken = accessToken;
            this.expiryTime = LocalDateTime.now().plusNanos(ttlMillis * 1_000_000);
        }

        public String getAccessToken() {
            return accessToken;
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiryTime);
        }
    }

    /**
     * KIS Credentials DTO.
     *
     * <p>{@code baseUrl} 은 실전 매매/조회 도메인({@code kis.base-url})이다.
     * 호출부는 이 값을 {@code KisApiClient} 의 8-arg get/post 로 넘긴다.
     */
    public record KisCredentials(
            String appKey,
            String appSecret,
            String accountNumber,
            String accountProductCode,
            String baseUrl
    ) {}
}
