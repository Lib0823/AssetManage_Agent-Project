package com.inbeom.apiserver.client;

import com.inbeom.apiserver.config.UpbitProperties;
import com.inbeom.apiserver.exception.UpbitApiException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 업비트 Open API HTTP 클라이언트.
 *
 * <p><b>{@code KisApiClient} 를 재사용할 수 없다.</b> KIS 는 OAuth 토큰을 받아 24시간 캐시하지만
 * 업비트는 <b>요청마다 JWT 를 새로 서명</b>한다. 캐시할 토큰이 없고, 서명 대상에 요청 파라미터가
 * 들어가므로 인증이 요청 내용에 묶여 있다.
 *
 * <h2>이 클래스에서 틀리기 쉬운 세 가지</h2>
 * <ol>
 *   <li><b>Secret Key 는 Base64 가 아니다.</b> 디코딩하면 서명이 전부 깨진다 — UTF-8 raw bytes 를
 *       그대로 키로 쓴다.</li>
 *   <li><b>POST 바디의 {@code query_hash} 는 JSON 문자열이 아니라 바디를 쿼리스트링으로 바꾼
 *       결과를 해싱</b>한다. JSON 을 해싱하면 <b>조회는 전부 정상인데 주문만 401</b> 이 되어
 *       원인을 찾기 어렵다.</li>
 *   <li><b>파라미터가 없으면 {@code query_hash} 를 아예 넣지 않는다.</b> 빈 문자열의 해시를 넣으면
 *       {@code GET /v1/accounts} 가 거부된다.</li>
 * </ol>
 *
 * <h2>서명 알고리즘이 HS256 인 이유</h2>
 * 업비트 문서는 HS512 를 권장하지만 jjwt 로는 <b>구현할 수 없다</b>. 업비트 Secret Key 는 40자
 * (320비트)인데 jjwt 가 RFC 7518 §3.2 의 최소 키 길이(HS512 = 512비트)를 강제한다. HS256 은
 * 256비트를 요구하므로 320비트로 충족된다. 업비트 공식 Java 예제들도 HMAC256 을 쓴다.
 * <p><b>미검증 항목</b>: 실제 키가 없어 업비트가 HS256 을 수용하는지 실호출로 확인하지 못했다.
 * 거부된다면 jjwt 대신 {@code javax.crypto.Mac("HmacSHA512")} + 수동 base64url 인코딩이 필요하다.
 *
 * <h2>실패 정규화</h2>
 * 모든 실패는 {@link UpbitApiException} 하나로 바뀐다. 업비트는 정상 에러 JSON, {@code error.name}
 * 이 숫자인 JSON(404/400), 점검 중 HTML, 5xx 를 모두 낸다 — DTO 로 고정하면 역직렬화가 깨지고
 * 조회 경로의 graceful degrade 가 무너진다. 그래서 응답 본문은 <b>정규식으로 느슨하게</b> 읽는다.
 */
@Slf4j
@Component
public class UpbitApiClient {

    /** 한국 리전 기본 주소. 실제 사용 주소는 {@code upbit.base-url} 로 덮어쓸 수 있다. */
    public static final String BASE_URL = "https://api.upbit.com";

    /** 업비트가 IP 화이트리스트 위반에 쓰는 에러 이름. */
    private static final String ERROR_IP_NOT_ALLOWED = "no_authorization_i_p";

    /** 시세 버킷은 업비트가 세는 단위와 같게 그룹별로 나눈다. */
    private static final String QUOTE_BUCKET_PREFIX = "ratelimit:upbit:quote:";
    private static final String ORDER_BUCKET_PREFIX = "ratelimit:upbit:order:";

    private final RestTemplate restTemplate = buildRestTemplate();
    private final UpbitProperties properties;

    /** {@code kis.resilience.rate-limit.enabled=false} 면 빈이 없다 → 제한 없이 통과(fail-open). */
    @Autowired(required = false)
    private KisRateLimiter rateLimiter;

    public UpbitApiClient(UpbitProperties properties) {
        this.properties = properties;
    }

    /**
     * 타임아웃은 {@code KisApiClient} 와 같은 수준으로 맞춘다(connect 5s / read 18s).
     *
     * <p><b>{@code USE_BIG_DECIMAL_FOR_FLOATS} 를 켜는 것이 이 메서드의 핵심이다.</b> 기본 설정에서는
     * JSON 실수가 {@code Object} 자리에 {@code Double} 로 들어오는데, 코인 잔고는
     * {@code 0.00012345} 같은 소수라 double 을 거치는 순간 정밀도가 자산 금액만큼 깎인다.
     * 파싱 시점에 {@link java.math.BigDecimal} 로 받아야 이후 {@code toPlainString()} 까지
     * 원문 그대로 흘러간다.
     */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(18000);

        RestTemplate template = new RestTemplate(factory);
        JsonMapper.Builder mapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        template.getMessageConverters().removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
        template.getMessageConverters().add(new JacksonJsonHttpMessageConverter(mapper));
        return template;
    }

    // ------------------------------------------------------------------
    // JWT
    // ------------------------------------------------------------------

    /**
     * 업비트 인증 JWT 를 만든다.
     *
     * <p>{@code params} 는 <b>삽입 순서가 유지되는 맵</b>({@link LinkedHashMap})이어야 한다.
     * 업비트는 요청에 실제로 실리는 순서 그대로의 쿼리스트링을 해싱하므로, 정렬하거나 순서가
     * 뒤섞이면 서버가 계산한 해시와 달라져 401 이 난다.
     *
     * @param params GET 은 쿼리 파라미터, POST 는 <b>JSON 바디의 필드</b>. 비어 있으면
     *               {@code query_hash}/{@code query_hash_alg} 를 넣지 않는다.
     */
    public static String buildJwt(String accessKey, String secretKey, Map<String, String> params) {
        JwtBuilder builder = Jwts.builder()
                .claim("access_key", accessKey)
                .claim("nonce", UUID.randomUUID().toString());

        if (params != null && !params.isEmpty()) {
            builder.claim("query_hash", sha512Hex(toQueryString(params)))
                    .claim("query_hash_alg", "SHA512");
        }

        // Base64 디코딩하지 않는다 — 업비트 Secret Key 는 Base64 문자열이 아니다.
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /**
     * {@code k=v&k=v} — <b>URL 인코딩하지 않은 원문</b>, <b>정렬하지 않은 삽입 순서</b>.
     *
     * <p>이 두 조건이 곧 계약이다. 인코딩하거나 정렬하면 업비트가 계산한 해시와 어긋난다.
     */
    static String toQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String sha512Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }

    // ------------------------------------------------------------------
    // 요청
    // ------------------------------------------------------------------

    /**
     * 인증이 필요 없는 시세 조회.
     *
     * <p>rate limit 버킷은 <b>IP 단위 고정 버킷</b>이다 — 업비트의 시세 한도가 IP 에 걸리므로
     * 전체 사용자가 서버 공인 IP 하나의 한도를 공유한다. 사용자별로 나누면 실제 한도를 넘긴다.
     */
    public <T> ResponseEntity<T> getPublic(String path, Map<String, String> queryParams, Class<T> responseType) {
        acquireQuoteToken(path);
        return exchange(HttpMethod.GET, buildUrl(path, queryParams), plainHeaders(), null, responseType, path);
    }

    /**
     * 인증이 필요한 GET ({@code /v1/accounts} 등).
     *
     * <p>{@code queryParams} 가 비어 있으면 {@code query_hash} 없이 서명된다.
     *
     * <p><b>주의 — 서명과 URL 의 인코딩이 다르다.</b> {@code query_hash} 는 인코딩하지 않은 원문을
     * 해싱하는데({@link #toQueryString}), URL 은 {@link #buildUrl} 이 퍼센트 인코딩한다. 값에 인코딩
     * 대상 문자가 들어가면 업비트가 계산한 해시와 어긋나 <b>원인이 드러나지 않는 401</b> 이 난다.
     * 현재 유일한 호출처인 {@code /v1/accounts} 는 파라미터가 없어 발현하지 않지만, 주문 조회·취소처럼
     * 파라미터 있는 인증 GET 을 추가하는 순간 걸린다. 그래서 아래에서 미리 끊는다.
     */
    public <T> ResponseEntity<T> getAuthenticated(String path, Map<String, String> queryParams,
                                                  String accessKey, String secretKey, Class<T> responseType) {
        requireEncodingSafeParams(queryParams);
        acquireOrderToken(accessKey);
        String jwt = buildJwt(accessKey, secretKey, queryParams);
        return exchange(HttpMethod.GET, buildUrl(path, queryParams), authHeaders(jwt), null, responseType, path);
    }

    /**
     * 인증 GET 파라미터가 퍼센트 인코딩으로 변형되지 않는지 확인한다.
     *
     * <p>변형되면 서명({@code query_hash}, 원문 기준)과 실제 URL(인코딩됨)이 달라진다. 그 상태를
     * 그대로 보내면 401 만 돌아오고 왜 틀렸는지는 드러나지 않으므로, 나가기 전에 끊어 원인을 남긴다.
     * 이 예외가 뜬다면 값을 고칠 게 아니라 <b>서명과 URL 이 같은 문자열을 쓰도록 구현을 고쳐야 한다.</b>
     */
    static void requireEncodingSafeParams(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            String value = e.getValue();
            // buildUrl 이 쓰는 UriComponentsBuilder.encode() 와 같은 규칙(쿼리 파라미터 성분)이다.
            if (value != null
                    && !value.equals(UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException(
                        "Upbit 인증 GET 파라미터 '" + e.getKey() + "' 의 값이 URL 인코딩으로 변형된다. "
                                + "query_hash 는 원문을 해싱하므로 서명과 URL 이 어긋나 401 이 난다. "
                                + "getAuthenticated 의 서명/URL 인코딩을 일치시킨 뒤 사용할 것.");
            }
        }
    }

    /**
     * 인증이 필요한 POST ({@code /v1/orders}).
     *
     * <p>바디는 JSON 으로 나가지만 <b>서명 대상은 같은 바디를 쿼리스트링으로 바꾼 문자열</b>이다.
     * 이 비대칭이 이 클라이언트에서 가장 값비싼 함정이다.
     *
     * @param body 삽입 순서가 유지되는 맵. 값은 전부 문자열이며 업비트가 그대로 받아들인다
     *             (수량·가격은 {@code BigDecimal.toPlainString()} 으로 만든 문자열이어야 한다).
     */
    public <T> ResponseEntity<T> postAuthenticated(String path, Map<String, String> body,
                                                   String accessKey, String secretKey, Class<T> responseType) {
        acquireOrderToken(accessKey);
        String jwt = buildJwt(accessKey, secretKey, body);
        HttpHeaders headers = authHeaders(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return exchange(HttpMethod.POST, baseUrl() + path, headers, body, responseType, path);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String url, HttpHeaders headers,
                                           Object body, Class<T> responseType, String path) {
        try {
            return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), responseType);
        } catch (HttpStatusCodeException e) {
            throw normalize(e, path);
        } catch (Exception e) {
            // 네트워크 오류, 그리고 점검 중 HTML 응답을 만난 역직렬화 실패까지 여기로 온다.
            log.warn("Upbit call failed: path={} cause={}", path, e.toString());
            throw new UpbitApiException("업비트 API 호출에 실패했습니다: " + path, e);
        }
    }

    /**
     * HTTP 오류 응답 → {@link UpbitApiException}.
     *
     * <p>본문을 DTO 로 역직렬화하지 않는다. {@code error.name} 이 문자열({@code
     * "no_authorization_token"})일 때도 있고 숫자({@code 404})일 때도 있어 타입 고정이 불가능하고,
     * 점검 중에는 JSON 이 아예 아닐 수도 있기 때문이다.
     */
    private UpbitApiException normalize(HttpStatusCodeException e, String path) {
        String body = e.getResponseBodyAsString();
        int status = e.getStatusCode().value();

        if (body != null && body.contains(ERROR_IP_NOT_ALLOWED)) {
            log.error("Upbit rejected the request because the server IP is not on the key's allow list: path={}", path);
            return UpbitApiException.ipNotAllowed(
                    "서버 IP가 업비트 API 키의 허용 IP 목록에 없습니다. 업비트에서 허용 IP를 다시 등록해 주세요.");
        }
        // 429 = 초당 한도 초과, 418 = 429 누적으로 인한 일시 차단. 둘 다 "잠시 후 재시도".
        if (status == 429 || status == 418) {
            log.warn("Upbit rate limited us: status={} path={}", status, path);
            return UpbitApiException.rateLimited("업비트 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }

        String message = extractErrorMessage(body);
        log.warn("Upbit error response: status={} path={} message={}", status, path, message);
        return new UpbitApiException("업비트 API 오류(" + status + "): " + message);
    }

    /** {@code {"error":{"message":"..."}}} 에서 message 만 느슨하게 뽑는다. 실패하면 원문 앞부분. */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "응답 본문 없음";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ------------------------------------------------------------------
    // rate limit
    // ------------------------------------------------------------------

    private void acquireQuoteToken(String path) {
        if (rateLimiter == null) {
            return;
        }
        String bucket = QUOTE_BUCKET_PREFIX + quoteGroupOf(path);
        UpbitProperties.Quote quote = properties.getQuote();
        if (!rateLimiter.tryAcquire(bucket, quote.getCapacity(), quote.getRefillPerSecond())) {
            throw UpbitApiException.rateLimited(
                    "업비트 시세 조회가 서버 한도에 걸렸습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private void acquireOrderToken(String accessKey) {
        if (rateLimiter == null) {
            return;
        }
        String bucket = ORDER_BUCKET_PREFIX + KisRateLimiter.hashCredential(accessKey);
        UpbitProperties.Order order = properties.getOrder();
        if (!rateLimiter.tryAcquire(bucket, order.getCapacity(), order.getRefillPerSecond())) {
            throw UpbitApiException.rateLimited(
                    "업비트 요청이 서버 한도에 걸렸습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 경로 → 업비트 시세 그룹({@code market}/{@code ticker}/{@code orderbook}/{@code candle}).
     *
     * <p>업비트는 그룹별로 독립 카운트하므로 버킷도 그룹별이어야 한다. 하나로 합치면 실제 한도보다
     * 과하게 조여, 캔들 조회가 티커 조회를 막는 일이 생긴다.
     */
    private String quoteGroupOf(String path) {
        if (path.startsWith("/v1/candles")) {
            return "candle";
        }
        if (path.startsWith("/v1/orderbook")) {
            return "orderbook";
        }
        if (path.startsWith("/v1/ticker")) {
            return "ticker";
        }
        if (path.startsWith("/v1/market")) {
            return "market";
        }
        return "other";
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String baseUrl() {
        String configured = properties.getBaseUrl();
        return (configured == null || configured.isBlank()) ? BASE_URL : configured;
    }

    /**
     * 실제 전송용 URL. <b>여기서는 값을 URL 인코딩한다</b> — 서명 대상(쿼리스트링 원문)과 달리
     * 전송되는 URL 은 인코딩되어야 한다. 마켓 코드({@code KRW-BTC})와 콤마 목록은 인코딩되어도
     * 업비트가 동일하게 해석한다.
     */
    // package-private: UpbitUrlEncodingTest 가 다중 마켓 조회의 이중 인코딩 회귀를 잡는다.
    String buildUrl(String path, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl() + path;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl() + path);
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            builder.queryParam(e.getKey(), e.getValue());
        }
        // encode() 에 맡기고 값을 미리 URLEncoder 로 인코딩하지 않는다 — 그러면 '%' 가 다시
        // 인코딩돼 다중 마켓 조회의 콤마가 %252C 가 되고, 업비트가 404 Code not found 를 준다.
        return builder.encode().build().toUriString();
    }

    private HttpHeaders plainHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private HttpHeaders authHeaders(String jwt) {
        HttpHeaders headers = plainHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        return headers;
    }
}
