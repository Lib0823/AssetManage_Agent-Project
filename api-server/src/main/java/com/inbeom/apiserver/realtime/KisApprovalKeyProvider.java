package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.service.KisQuoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KIS 실시간 WebSocket 접속용 {@code approval_key} 발급/캐시.
 *
 * <p>발급: {@code POST {quote-base-url}/oauth2/Approval}
 * body {@code {grant_type:"client_credentials", appkey, secretkey}} → {@code approval_key}.
 * 연결당 1개의 approval_key 가 필요하며, 시세(quote) 앱키로 발급한다
 * ({@link KisQuoteService} getter 만 읽음 — KisQuoteService 편집 금지).
 *
 * <p><b>MUST-VERIFY — body 필드명 {@code secretkey}</b>.
 * 일반 OAuth 토큰({@code /oauth2/tokenP})은 {@code appsecret} 을 쓰지만, WebSocket 접속키
 * 발급({@code /oauth2/Approval})은 KIS 명세상 {@code secretkey} 를 쓴다고 알려져 있다.
 * 두 필드명은 엔드포인트가 다르며 혼용 시 발급 실패한다. 필드명은 아래 {@link #FIELD_SECRET_KEY}
 * 한 곳에만 둔다. 라이브 검증: 실키로 발급 호출 시 200 + approval_key 수신이면 확정,
 * 4xx({@code EGW00...})면 {@code appsecret} 로 교체해 재확인.
 *
 * <p>비활성(키 미설정)/실패 시 예외를 던지지 않고 null 반환 → 상향 연결 생략(graceful degrade).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApprovalKeyProvider {

    /** MUST-VERIFY: WebSocket Approval body 의 secret 필드명 (vs OAuth tokenP 의 appsecret). */
    private static final String FIELD_SECRET_KEY = "secretkey";

    private static final String APPROVAL_PATH = "/oauth2/Approval";

    private final KisQuoteService kisQuoteService;

    private final RestTemplate restTemplate = buildRestTemplate();

    // approval_key 는 연결당 1개·재사용 가능. 단일 캐시 슬롯. 재연결 시 forceRefresh 로 재발급.
    private final AtomicReference<String> cachedApprovalKey = new AtomicReference<>();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        return new RestTemplate(factory);
    }

    /**
     * Phase 1 시세 연결용 자격증명 값객체. {@link KisQuoteService} 의 quote-* 설정을 읽기만 한다.
     */
    public ConnectionCredentials quoteCredentials() {
        return new ConnectionCredentials(
                kisQuoteService.getQuoteAppKey(),
                kisQuoteService.getQuoteAppSecret(),
                kisQuoteService.getQuoteBaseUrl()
        );
    }

    /**
     * 캐시된 approval_key 반환, 없으면 발급. 비활성/실패 시 null.
     */
    public String getApprovalKey(ConnectionCredentials creds) {
        String cached = cachedApprovalKey.get();
        if (cached != null) {
            return cached;
        }
        return issue(creds);
    }

    /**
     * approval_key 강제 재발급 (재연결 시). 실패 시 null 이며 캐시는 비운다.
     */
    public String refreshApprovalKey(ConnectionCredentials creds) {
        cachedApprovalKey.set(null);
        return issue(creds);
    }

    private synchronized String issue(ConnectionCredentials creds) {
        // 동기화 블록 진입 사이에 다른 스레드가 이미 발급했을 수 있음 → 재확인
        String existing = cachedApprovalKey.get();
        if (existing != null) {
            return existing;
        }
        if (creds == null || !creds.isUsable()) {
            log.warn("[realtime] KIS quote credentials not configured; approval_key unavailable");
            return null;
        }
        try {
            String url = creds.approvalBaseUrl() + APPROVAL_PATH;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // LinkedHashMap: 필드 순서 보존(디버깅 가독). secretkey 는 MUST-VERIFY 상수 사용.
            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", creds.appKey());
            body.put(FIELD_SECRET_KEY, creds.appSecret());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> respBody = response.getBody();
            if (response.getStatusCode() == HttpStatus.OK && respBody != null) {
                Object key = respBody.get("approval_key");
                if (key instanceof String s && !s.isBlank()) {
                    cachedApprovalKey.set(s);
                    log.info("[realtime] KIS approval_key issued (len={})", s.length());
                    return s;
                }
                log.warn("[realtime] approval response missing approval_key: keys={}", respBody.keySet());
                return null;
            }
            log.warn("[realtime] approval non-OK response: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            // MUST-VERIFY 단서: 4xx(EGW00xx) 면 body 필드명(secretkey↔appsecret) 점검.
            log.warn("[realtime] approval_key issue failed: {} (verify body field '{}')",
                    e.getMessage(), FIELD_SECRET_KEY);
            return null;
        }
    }
}
