package com.inbeom.apiserver.client;

import com.inbeom.apiserver.config.KisResilienceProperties;
import com.inbeom.apiserver.config.KisResilienceProperties.CachePolicy;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 모든 KIS 호출이 지나는 단일 관문.
 *
 * <p>여기에 <b>rate limit</b>({@link KisRateLimiter})과 <b>응답 캐시 + stale-if-error</b>
 * ({@link KisResponseCache})가 붙어 있다. 두 기능을 서비스 계층이 아니라 이 지점에 둔 이유는,
 * KIS 를 부르는 15곳이 넘는 호출부가 전부 이 메서드로 수렴하기 때문이다 — 어느 한 곳이 우회하면
 * 한도가 새고, 새 호출부가 생길 때마다 적용을 잊는 문제가 구조적으로 사라진다.
 *
 * <p>캐시는 <b>allowlist</b> 방식이다({@code KisResilienceProperties.Cache#policyFor}). 관문이
 * 공통이라는 것은 잔고·주문가능금액·체결내역도 여기를 지난다는 뜻이라, 무엇이든 캐시하면 매매
 * 판단이 낡은 값으로 이뤄진다. 그래서 종목 상세 화면이 반복 조회하는 읽기 전용 시세/재무 TR 만 연다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiClient {

    /**
     * 캐시에서 나온 응답임을 호출부에 알리는 헤더. {@code KisQuoteClient}/{@code CompanyInfoService}
     * 가 이 값을 보고 "최신 아님" 안내(notice)를 붙인다.
     *
     * <p>반환 타입을 바꾸지 않고 헤더로 실어 보내는 쪽을 택했다 — {@code ResponseEntity} 는 원래
     * 헤더를 나르는 타입이고(HTTP 캐시 의미론과도 일치), 15곳 넘는 호출부와 기존 테스트가 그대로
     * 컴파일된다. 헤더가 없으면 자연히 "캐시 아님"으로 읽힌다.
     */
    public static final String CACHE_HEADER = "X-Kis-Cache";
    public static final String CACHE_HIT = "HIT";
    public static final String CACHE_STALE = "STALE";

    // KIS 응답이 느리거나 도달 불가일 때 호출이 수십 초(OS 기본) 매달리지 않도록 타임아웃 지정.
    // 호출부는 예외를 잡아 graceful degrade(빈값/캐시 폴백) 하므로 빠른 실패가 바람직하다.
    private final RestTemplate restTemplate = buildRestTemplate();

    /**
     * rate limit / 캐시는 없어도 동작해야 하는 부가 계층이다(설정으로 끌 수 있고, Redis 가 없는
     * 단위 테스트는 {@code new KisApiClient()} 로 이 클래스를 직접 만든다). 그래서 생성자 필수
     * 의존이 아니라 선택 주입으로 받고, null 이면 조용히 우회한다.
     */
    @Autowired(required = false)
    private KisRateLimiter rateLimiter;

    @Autowired(required = false)
    private KisResponseCache responseCache;

    @Autowired(required = false)
    private KisResilienceProperties resilienceProperties;

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // connect 는 짧게(도달 불가 빠른 실패), read 는 넉넉히: 거래내역(inquire-daily-ccld,
        // 최근 3개월)은 시세보다 느려 7초로는 잘려 500 이 나므로 18초로 둔다. (OS 기본 ~75초 방지)
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(18000);
        return new RestTemplate(factory);
    }

    @Value("${kis.base-url}")
    private String kisBaseUrl;

    /**
     * Call KIS API with authentication headers
     */
    public <T> ResponseEntity<T> callKisApi(
            String endpoint,
            HttpMethod method,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Object requestBody,
            Class<T> responseType
    ) {
        return callKisApi(kisBaseUrl, endpoint, method, trId, kisToken, appKey, appSecret, requestBody, responseType);
    }

    /**
     * Call KIS API with an explicit base URL (e.g. 시세/재무 전용 도메인).
     *
     * 매매 흐름은 주입된 {@code kisBaseUrl} 을 그대로 쓰고, CompanyInfoService 의
     * 시세/재무 호출만 {@code kis.quote-base-url} 을 명시적으로 넘긴다.
     */
    public <T> ResponseEntity<T> callKisApi(
            String baseUrl,
            String endpoint,
            HttpMethod method,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Object requestBody,
            Class<T> responseType
    ) {
        String resolvedBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : kisBaseUrl;
        log.debug("KIS call: baseUrl={}, trId={}", resolvedBaseUrl, trId);

        CachePolicy policy = cachePolicyFor(method, trId);
        String cacheKey = (policy != null) ? responseCache.keyOf(resolvedBaseUrl, endpoint, trId) : null;

        // 1) 신선한 캐시가 있으면 KIS 를 호출하지 않는다 (토큰도 소비하지 않는다).
        KisResponseCache.Entry<T> cached =
                (policy != null) ? responseCache.find(cacheKey, responseType, policy) : null;
        if (cached != null && cached.fresh()) {
            log.debug("KIS cache hit: trId={}, endpoint={}", trId, endpoint);
            return cachedResponse(cached.body(), CACHE_HIT);
        }

        // 2) 토큰 버킷. 여기서 거부되면 소켓조차 열리지 않는다 —
        //    호출부(특히 Kafka 매매 컨슈머)가 "KIS 미접촉"을 구분할 수 있어야 하므로 전용 예외를 던진다.
        if (rateLimiter != null && !rateLimiter.tryAcquire(appKey)) {
            if (cached != null) {
                log.warn("KIS rate limited; serving stale cache: trId={}, endpoint={}", trId, endpoint);
                return cachedResponse(cached.body(), CACHE_STALE);
            }
            throw new KisRateLimitExceededException(
                    "KIS 호출 한도를 초과해 요청을 보내지 않았습니다 (trId=" + trId + ")");
        }

        // 3) 실제 호출. 실패하면 grace 기간 안의 마지막 성공값으로 폴백한다(stale-if-error).
        try {
            ResponseEntity<T> response = exchange(resolvedBaseUrl + endpoint, endpoint, method,
                    trId, kisToken, appKey, appSecret, requestBody, responseType);
            if (policy != null) {
                if (isSuccessBody(response.getBody())) {
                    responseCache.put(cacheKey, response.getBody(), policy);
                } else if (cached != null) {
                    // rt_cd != 0 도 "조회 실패"다. 그대로 넘기면 화면이 빈 값으로 degrade 하므로
                    // 마지막 성공값이 있으면 그쪽이 사용자에게 더 쓸모 있다.
                    log.warn("KIS returned rt_cd!=0; serving stale cache: trId={}", trId);
                    return cachedResponse(cached.body(), CACHE_STALE);
                }
            }
            return response;
        } catch (KisApiException e) {
            if (cached != null) {
                log.warn("KIS call failed ({}); serving stale cache: trId={}", e.getMessage(), trId);
                return cachedResponse(cached.body(), CACHE_STALE);
            }
            throw e;
        }
    }

    /** 캐시/rate limit 을 거치지 않는 순수 HTTP 호출. 실패는 전부 {@link KisApiException} 으로 정규화된다. */
    private <T> ResponseEntity<T> exchange(
            String url,
            String endpoint,
            HttpMethod method,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Object requestBody,
            Class<T> responseType
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + kisToken);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", trId);
        headers.set("custtype", "P");

        HttpEntity<?> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<T> response = restTemplate.exchange(url, method, request, responseType);
            log.debug("KIS API call success: {} {}, status={}", method, endpoint, response.getStatusCode());
            return response;
        } catch (HttpStatusCodeException e) {
            // KIS 가 4xx/5xx + 에러 본문을 준 경우: 상태·본문(rt_cd/msg1 등)을 보존해
            // 실제 실패 사유가 generic 500 으로 가려지지 않게 한다.
            String responseBody = e.getResponseBodyAsString();
            log.error("KIS API HTTP error: {} {} status={} body={}", method, endpoint, e.getStatusCode(), responseBody);
            String detail = "KIS API error (HTTP " + e.getStatusCode().value() + "): " + responseBody;
            if (e.getStatusCode().is4xxClientError()) {
                throw KisApiException.clientError(detail, e);
            }
            throw KisApiException.serverError(detail, e);
        } catch (ResourceAccessException e) {
            // 연결 실패/타임아웃
            log.error("KIS API network error: {} {}", method, endpoint, e);
            throw KisApiException.networkError("KIS API network error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("KIS API call failed: {} {}", method, endpoint, e);
            throw KisApiException.serverError("KIS API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 이 호출이 캐시 대상인지. GET 만 대상이다 — POST 는 주문/취소 같은 부작용 있는 요청이라
     * 캐시하면 "주문이 나간 것처럼 보이지만 실제로는 안 나간" 상태가 만들어진다.
     *
     * @return 캐시 비활성이거나 allowlist 밖의 TR 이면 null
     */
    private CachePolicy cachePolicyFor(HttpMethod method, String trId) {
        if (responseCache == null || resilienceProperties == null || !HttpMethod.GET.equals(method)) {
            return null;
        }
        return resilienceProperties.getCache().policyFor(trId);
    }

    /**
     * KIS 는 HTTP 200 이면서 본문 {@code rt_cd} 로 성공/실패를 알린다. 성공(0)만 캐시한다 —
     * 오류 본문을 캐시하면 TTL 동안 같은 오류를 되돌려주고 stale 폴백까지 오염된다.
     */
    private boolean isSuccessBody(Object body) {
        return body instanceof Map<?, ?> map && "0".equals(String.valueOf(map.get("rt_cd")));
    }

    private <T> ResponseEntity<T> cachedResponse(T body, String cacheStatus) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CACHE_HEADER, cacheStatus);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** 이 응답이 신선하지 않은 캐시(stale)에서 나왔는지 — 호출부가 "최신 아님" 안내를 붙일 때 쓴다. */
    public static boolean isStale(ResponseEntity<?> response) {
        return response != null && CACHE_STALE.equals(response.getHeaders().getFirst(CACHE_HEADER));
    }

    /**
     * GET request to KIS API
     */
    public <T> ResponseEntity<T> get(
            String endpoint,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Map<String, String> queryParams,
            Class<T> responseType
    ) {
        return get(kisBaseUrl, endpoint, trId, kisToken, appKey, appSecret, queryParams, responseType);
    }

    /**
     * GET request to KIS API with an explicit base URL (실전 시세/재무 도메인용).
     */
    public <T> ResponseEntity<T> get(
            String baseUrl,
            String endpoint,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Map<String, String> queryParams,
            Class<T> responseType
    ) {
        // Build query string
        StringBuilder urlBuilder = new StringBuilder(endpoint);
        if (queryParams != null && !queryParams.isEmpty()) {
            urlBuilder.append("?");
            queryParams.forEach((key, value) ->
                    urlBuilder.append(key).append("=").append(value).append("&")
            );
            urlBuilder.setLength(urlBuilder.length() - 1); // Remove last &
        }

        return callKisApi(baseUrl, urlBuilder.toString(), HttpMethod.GET, trId, kisToken, appKey, appSecret, null, responseType);
    }

    /**
     * POST request to KIS API
     */
    public <T> ResponseEntity<T> post(
            String endpoint,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Object requestBody,
            Class<T> responseType
    ) {
        return callKisApi(endpoint, HttpMethod.POST, trId, kisToken, appKey, appSecret, requestBody, responseType);
    }

    /**
     * POST request with an explicit base URL.
     */
    public <T> ResponseEntity<T> post(
            String baseUrl,
            String endpoint,
            String trId,
            String kisToken,
            String appKey,
            String appSecret,
            Object requestBody,
            Class<T> responseType
    ) {
        return callKisApi(baseUrl, endpoint, HttpMethod.POST, trId, kisToken, appKey, appSecret, requestBody, responseType);
    }
}
