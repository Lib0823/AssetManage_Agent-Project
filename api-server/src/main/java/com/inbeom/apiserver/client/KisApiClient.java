package com.inbeom.apiserver.client;

import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.service.KisAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiClient {

    // KIS 응답이 느리거나 도달 불가일 때 호출이 수십 초(OS 기본) 매달리지 않도록 타임아웃 지정.
    // 호출부는 예외를 잡아 graceful degrade(빈값/캐시 폴백) 하므로 빠른 실패가 바람직하다.
    private final RestTemplate restTemplate = buildRestTemplate();

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
     * 주어진 base URL 이 실전(real) 도메인인지 판정.
     * - 실전: openapi.koreainvestment.com  · 모의: openapivts.koreainvestment.com
     * (모의 도메인은 "openapivts." 라서 "openapi.koreainvestment.com" 을 포함하지 않는다.)
     */
    private boolean isRealDomain(String baseUrl) {
        return baseUrl != null && baseUrl.contains("openapi.koreainvestment.com");
    }

    /** 하위호환: 전역 base-url(모의 기본) 기준 변환. */
    public String convertTrId(String baseTrId) {
        return convertTrId(baseTrId, kisBaseUrl);
    }

    /**
     * Convert TR_ID based on the call's base URL (사용자별 모의/실전 도메인).
     * - Virtual (openapivts): VTTC*  · Real (openapi): TTTC*
     *
     * IMPORTANT: VTTC/TTTC 국내 매매 TR 만 도메인 종속이라 변환한다. FHKST(시세/재무)
     * 및 해외 TR(VTTS/VTTT/HHDFS)은 변환하지 않고 그대로 반환한다(해외는 호출부가 V/T 확정).
     *
     * @param baseTrId Base TR_ID (e.g., "VTTC8434R")
     * @param baseUrl  이 호출의 도메인 (계정 모드로 결정)
     */
    public String convertTrId(String baseTrId, String baseUrl) {
        if (baseTrId == null || baseTrId.length() < 4) {
            return baseTrId;
        }
        String head = baseTrId.substring(0, 4);
        if (!head.equals("VTTC") && !head.equals("TTTC")) {
            return baseTrId;
        }
        String suffix = baseTrId.substring(4); // "8434R" 부분
        String prefix = isRealDomain(baseUrl) ? "TTTC" : "VTTC";
        return prefix + suffix;
    }

    /**
     * Call KIS API with authentication headers
     * TR_ID는 base URL에 따라 자동 변환됩니다 (VTTC ↔ TTTC)
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
     * Call KIS API with an explicit base URL (e.g. 실전 시세/재무 도메인).
     *
     * 기존 매매 흐름은 주입된 {@code kisBaseUrl}(모의 도메인)을 그대로 사용하고,
     * CompanyInfoService 의 시세/재무 호출만 실전 도메인을 명시적으로 넘긴다.
     * TR_ID 변환은 VTTC/TTTC 매매 prefix 에만 적용되므로 FHKST 시세/재무 TR_ID 는 그대로 전송된다.
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
        String url = resolvedBaseUrl + endpoint;

        // TR_ID 자동 변환: 이 호출의 도메인(resolvedBaseUrl) 기준 Virtual→VTTC* / Real→TTTC*. FHKST*/해외 TR 등은 변환되지 않음.
        String convertedTrId = convertTrId(trId, resolvedBaseUrl);
        log.debug("KIS call: baseUrl={}, trId: {} → {}", resolvedBaseUrl, trId, convertedTrId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + kisToken);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", convertedTrId);  // 변환된 TR_ID 사용
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
     * POST request with an explicit base URL (사용자별 모의/실전 매매 도메인).
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
