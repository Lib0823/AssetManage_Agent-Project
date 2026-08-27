package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.kis.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KIS 시세/재무 전용 실전(real) 도메인 자격증명 + OAuth 토큰 관리.
 *
 * <p>KIS 시세(inquire-price)·재무(income-statement/financial-ratio/stability-ratio) API 는
 * 매매/잔고 흐름이 쓰는 사용자별 DB 자격증명({@link KisAuthService})과 완전히 분리된,
 * 설정(kis.quote-*) 기반의 앱 단위(app-level) 자격증명·토큰으로 호출한다.
 *
 * <p>토큰은 앱 단위(사용자별이 아님)이므로 단일 캐시 슬롯으로 충분하며 24h 캐시한다.
 * quote-app-key/secret 둘 다 비어있으면 비활성({@link #isQuoteEnabled()}=false)이며 토큰은 null 이다.
 */
@Slf4j
@Service
public class KisQuoteService {

    // 토큰 요청에 타임아웃을 둬서 KIS 가 느리거나 도달 불가일 때 요청이 수십 초 매달리지 않고
    // 빠르게 실패(→ 호출부가 null 처리/캐시 폴백)하도록 한다.
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        return new RestTemplate(factory);
    }

    @Value("${kis.quote-base-url:}")
    private String quoteBaseUrl;

    @Value("${kis.quote-app-key:}")
    private String quoteAppKey;

    @Value("${kis.quote-app-secret:}")
    private String quoteAppSecret;

    @Value("${kis.token-cache-ttl}")
    private long tokenCacheTtl;

    // 앱 단위 단일 토큰 캐시 (사용자별 아님)
    private final AtomicReference<QuoteTokenCache> tokenCache = new AtomicReference<>();

    /**
     * 시세/재무 연동 가능 여부 = quote app key/secret 둘 다 설정됨.
     */
    public boolean isQuoteEnabled() {
        return quoteAppKey != null && !quoteAppKey.isBlank()
                && quoteAppSecret != null && !quoteAppSecret.isBlank();
    }

    /**
     * 시세/재무 호출 대상 base URL (실전 도메인).
     */
    public String getQuoteBaseUrl() {
        return quoteBaseUrl;
    }

    public String getQuoteAppKey() {
        return quoteAppKey;
    }

    public String getQuoteAppSecret() {
        return quoteAppSecret;
    }

    /**
     * 실전 도메인 OAuth 토큰 획득 (24h 캐시). 비활성/실패 시 null.
     */
    public String getQuoteAccessToken() {
        if (!isQuoteEnabled()) {
            log.debug("KIS quote credentials not configured; quote token unavailable");
            return null;
        }

        QuoteTokenCache cached = tokenCache.get();
        if (cached != null && !cached.isExpired()) {
            log.debug("KIS quote token cache hit");
            return cached.accessToken();
        }

        try {
            String token = requestQuoteOAuthToken();
            if (token == null) {
                return null;
            }
            tokenCache.set(new QuoteTokenCache(token, tokenCacheTtl));
            log.info("KIS quote token cached, expires in {}ms", tokenCacheTtl);
            return token;
        } catch (Exception e) {
            log.warn("KIS quote OAuth token request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * {quote-base-url}/oauth2/tokenP 로 client_credentials OAuth 요청 (KisAuthService 미러).
     */
    private String requestQuoteOAuthToken() {
        String url = quoteBaseUrl + "/oauth2/tokenP";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", quoteAppKey);
        body.put("appsecret", quoteAppSecret);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<KisTokenResponse> response =
                restTemplate.postForEntity(url, request, KisTokenResponse.class);
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return response.getBody().getAccessToken();
        }
        log.warn("KIS quote OAuth non-OK response: {}", response.getStatusCode());
        return null;
    }

    /**
     * 실전 시세/재무 토큰 캐시 엔트리.
     */
    private record QuoteTokenCache(String accessToken, LocalDateTime expiryTime) {
        QuoteTokenCache(String accessToken, long ttlMillis) {
            this(accessToken, LocalDateTime.now().plusNanos(ttlMillis * 1_000_000));
        }

        boolean isExpired() {
            return LocalDateTime.now().isAfter(expiryTime);
        }
    }
}
