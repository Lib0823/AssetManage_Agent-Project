package com.inbeom.apiserver.client;

import com.inbeom.apiserver.dto.company.FinancialsResponse;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import com.inbeom.apiserver.service.CompanyInfoService;
import com.inbeom.apiserver.service.KisQuoteClient;
import com.inbeom.apiserver.service.KisQuoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * KIS rate limit(토큰 버킷) · 응답 캐시(stale-if-error) 통합 테스트.
 *
 * <p>{@code TradeOrderConsumerIntegrationTest} 와 같은 기조다 — 실제 인프라(Redis 컨테이너)를 띄우고
 * <b>프로덕션 코드 경로</b>({@code KisApiClient} → {@code KisRateLimiter}/{@code KisResponseCache},
 * 그 위의 {@code KisQuoteClient}/{@code CompanyInfoService})를 그대로 태운다.
 *
 * <p>KIS 로 나가는 HTTP 만 바꿔치기한다. 그냥 목이 아니라 <b>호출 횟수를 세는</b> 전송 계층인데,
 * 이 테스트가 증명하려는 명제가 전부 "KIS 를 실제로 몇 번 불렀는가"이기 때문이다 —
 * "한도 초과분은 KIS 를 <b>부르지 않고</b> 거부됐다", "TTL 안 반복 조회는 <b>1번만</b> 나갔다"는
 * 응답값으로는 확인할 수 없고 호출 카운터로만 직접 확인된다.
 *
 * <p>Docker 가 필요하므로 기본 {@code ./gradlew test} 에서 제외되고 {@code ./gradlew redisTest} 로만 실행된다.
 */
@Testcontainers
@Tag("redis")
@ActiveProfiles("test")
@SpringBootTest
@DisplayName("KIS rate limit · 응답 캐시 (Redis)")
class KisRateLimitAndCacheIntegrationTest {

    /** docker-compose 와 동일한 이미지. */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /** 초과 호출이 실제로 막히는지 보려면 값이 낮아야 관측 가능하다(운영 기본은 capacity 10 / refill 5). */
    private static final int CAPACITY = 5;

    /** 캐시 만료 후 재호출을 현실적인 시간에 검증하려고 짧게 줄인다(운영 30초). */
    private static final long QUOTE_TTL_MS = 2000L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // application-test.yml 은 둘 다 꺼두므로 여기서만 켠다.
        registry.add("kis.resilience.rate-limit.enabled", () -> "true");
        registry.add("kis.resilience.rate-limit.capacity", () -> CAPACITY);
        registry.add("kis.resilience.rate-limit.refill-per-second", () -> CAPACITY);
        registry.add("kis.resilience.cache.enabled", () -> "true");
        registry.add("kis.resilience.cache.quote-ttl", () -> QUOTE_TTL_MS + "ms");
        registry.add("kis.resilience.cache.quote-stale-grace", () -> "1h");
        registry.add("kis.resilience.cache.finance-ttl", () -> "1h");
    }

    private static final String QUOTE_APP_KEY = "SHARED-QUOTE-APP-KEY";
    private static final String USER_APP_KEY = "USER-1-TRADING-APP-KEY";
    private static final String REAL_BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final String PRICE_ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-price";
    /** 캐시 allowlist 밖의 TR — rate limit 만 관측하고 싶을 때 쓴다(잔고조회). */
    private static final String TR_BALANCE = "VTTC8434R";

    @Autowired
    private KisApiClient kisApiClient;

    @Autowired
    private KisQuoteClient kisQuoteClient;

    @Autowired
    private CompanyInfoService companyInfoService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 토큰 발급만 담당 — 이 테스트의 검증 대상이 아니고, 실제 KIS OAuth 를 타면 안 된다. */
    @MockitoBean
    private KisQuoteService kisQuoteService;

    private CountingKisTransport transport;

    @BeforeEach
    void setUp() {
        // 버킷/캐시가 테스트 간에 새지 않도록 초기화한다.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        transport = new CountingKisTransport();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(kisApiClient, "restTemplate");
        restTemplate.setRequestFactory(transport);

        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn("QUOTE_TOKEN");
        when(kisQuoteService.getQuoteBaseUrl()).thenReturn(REAL_BASE_URL);
        when(kisQuoteService.getQuoteAppKey()).thenReturn(QUOTE_APP_KEY);
        when(kisQuoteService.getQuoteAppSecret()).thenReturn("QUOTE_APP_SECRET");
    }

    // ==================================================================
    // (a) 토큰 버킷
    // ==================================================================

    @Test
    @DisplayName("(a) 한도 내 호출은 통과하고, 초과분은 KIS 를 호출하지 않고 거부된다")
    void overLimitCallsAreRejectedWithoutTouchingKis() {
        int attempts = CAPACITY * 3;
        int allowed = 0;
        int rejected = 0;

        for (int i = 0; i < attempts; i++) {
            try {
                callBalance(USER_APP_KEY);
                allowed++;
            } catch (KisRateLimitExceededException e) {
                rejected++;
            }
        }

        assertThat(rejected).as("한도를 넘긴 호출은 거부되어야 한다").isGreaterThan(0);
        assertThat(allowed).as("버스트 용량(+짧은 리필)만큼은 통과해야 한다")
                .isBetween(CAPACITY, CAPACITY + 2);

        // 핵심 단언: 거부된 호출은 KIS 로 나가지 않았다. 응답값이 아니라 전송 계층 카운터로 증명한다.
        assertThat(transport.calls()).as("KIS 실제 호출 횟수 = 통과한 호출 횟수여야 한다")
                .isEqualTo(allowed);
    }

    @Test
    @DisplayName("(a-2) 시간이 지나면 토큰이 리필되어 다시 호출할 수 있다")
    void bucketRefillsOverTime() {
        for (int i = 0; i < CAPACITY * 3; i++) {
            try {
                callBalance(USER_APP_KEY);
            } catch (KisRateLimitExceededException ignored) {
                // 버킷을 비우는 것이 목적
            }
        }
        assertThatThrownBy(() -> callBalance(USER_APP_KEY))
                .isInstanceOf(KisRateLimitExceededException.class);

        sleep(1200); // refill = CAPACITY/sec → 1초 남짓이면 가득 찬다

        int callsBefore = transport.calls();
        callBalance(USER_APP_KEY);
        assertThat(transport.calls()).isEqualTo(callsBefore + 1);
    }

    @Test
    @DisplayName("(b) 공유 시세 앱키 버킷과 사용자별 매매 앱키 버킷은 서로 독립이다")
    void bucketsAreIndependentPerAppKey() {
        // 공유 시세 키의 버킷을 완전히 소진시킨다.
        for (int i = 0; i < CAPACITY * 3; i++) {
            try {
                callBalance(QUOTE_APP_KEY);
            } catch (KisRateLimitExceededException ignored) {
                // 소진이 목적
            }
        }
        assertThatThrownBy(() -> callBalance(QUOTE_APP_KEY))
                .as("공유 시세 키는 소진 상태여야 한다")
                .isInstanceOf(KisRateLimitExceededException.class);

        // 사용자 매매 키는 아무 영향도 받지 않아야 한다 — 시세 폭주가 매매를 굶기면 안 된다.
        int callsBefore = transport.calls();
        for (int i = 0; i < CAPACITY; i++) {
            callBalance(USER_APP_KEY);
        }
        assertThat(transport.calls()).isEqualTo(callsBefore + CAPACITY);
    }

    // ==================================================================
    // (c) 캐시
    // ==================================================================

    @Test
    @DisplayName("(c) TTL 안에서 같은 종목을 100번 조회해도 KIS 호출은 1번뿐이다")
    void repeatedQuotesWithinTtlHitKisOnce() {
        int repeats = 100;

        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < repeats; i++) {
            Map<String, Object> price = kisQuoteClient.fetchCurrentPrice("005930");
            assertThat(price).isNotNull();
            assertThat(price.get("stck_prpr")).isEqualTo("71500");
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(elapsed).as("측정이 TTL(%dms) 안에서 끝나야 의미가 있다", QUOTE_TTL_MS)
                .isLessThan(QUOTE_TTL_MS);
        assertThat(transport.calls())
                .as("%d회 조회 중 KIS 로 나간 것은 1회여야 한다", repeats)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("(c-2) TTL 이 지나면 다시 KIS 를 호출한다 (영구 캐시가 아님)")
    void expiredCacheIsRefetched() {
        kisQuoteClient.fetchCurrentPrice("005930");
        assertThat(transport.calls()).isEqualTo(1);

        sleep(QUOTE_TTL_MS + 300);

        kisQuoteClient.fetchCurrentPrice("005930");
        assertThat(transport.calls()).as("TTL 만료 후에는 새로 조회해야 한다").isEqualTo(2);
    }

    @Test
    @DisplayName("(c-3) 종목이 다르면 캐시가 섞이지 않는다")
    void cacheKeyIsPerStock() {
        kisQuoteClient.fetchCurrentPrice("005930");
        kisQuoteClient.fetchCurrentPrice("000660");
        assertThat(transport.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("(c-4) stale-if-error: KIS 장애 시 마지막 성공값을 '최신 아님' 표시와 함께 반환한다")
    void staleIfErrorServesLastGoodValue() {
        kisQuoteClient.fetchCurrentPrice("005930");
        assertThat(transport.calls()).isEqualTo(1);

        sleep(QUOTE_TTL_MS + 300); // 신선도는 잃되 grace(1h) 안
        transport.failWith(HttpStatus.SERVICE_UNAVAILABLE);

        KisQuoteClient.QuoteResult result = kisQuoteClient.fetchCurrentPriceResult("005930");

        assertThat(transport.calls()).as("장애 확인을 위해 실제로 한 번은 시도한다").isEqualTo(2);
        assertThat(result.data()).as("마지막 성공값이 살아있어야 한다").isNotNull();
        assertThat(result.data().get("stck_prpr")).isEqualTo("71500");
        assertThat(result.stale()).as("낡은 값임이 호출부에 전달되어야 한다").isTrue();
    }

    @Test
    @DisplayName("(c-5) stale 값은 종목 상세 notice 로 '최신 아님' 안내가 붙는다")
    void staleValueSurfacesAsNotice() {
        companyInfoService.getFinancials("005930");
        transport.reset();
        transport.failWith(HttpStatus.SERVICE_UNAVAILABLE);
        sleep(QUOTE_TTL_MS + 300); // 시세만 신선도를 잃는다(재무 TTL 은 1h)

        FinancialsResponse response = companyInfoService.getFinancials("005930");

        assertThat(response.getRatios().getPer()).as("stale 폴백으로 값 자체는 남아야 한다").isNotNull();
        assertThat(response.getNotice())
                .as("'불러올 수 없음'이 아니라 '최신 아님'으로 구분해 안내해야 한다")
                .isEqualTo(KisQuoteClient.NOTICE_KIS_STALE);
    }

    @Test
    @DisplayName("(c-6) 캐시도 stale 도 없는 상태에서 KIS 가 죽으면 기존 degrade(null) 를 유지한다")
    void noCacheAndKisDownStillDegradesGracefully() {
        transport.failWith(HttpStatus.SERVICE_UNAVAILABLE);

        KisQuoteClient.QuoteResult result = kisQuoteClient.fetchCurrentPriceResult("005930");

        assertThat(result.data()).isNull();
        assertThat(result.stale()).isFalse();
    }

    @Test
    @DisplayName("(c-7) 재무 3종 + 시세는 두 번째 조회에서 KIS 를 한 번도 호출하지 않는다")
    void financialsAreServedFromCacheOnSecondCall() {
        companyInfoService.getFinancials("005930");
        int firstPass = transport.calls();
        assertThat(firstPass).as("손익 + 재무비율 + 안정성비율 + 시세 = 4회").isEqualTo(4);

        transport.reset();
        FinancialsResponse cached = companyInfoService.getFinancials("005930");

        assertThat(transport.calls()).as("두 번째는 전부 캐시에서 나온다").isZero();
        assertThat(cached.getRatios().getPer()).isNotNull();
        assertThat(cached.getNotice()).as("신선한 캐시는 안내 문구를 붙이지 않는다").isNull();
    }

    @Test
    @DisplayName("(c-9) 실측: 30명이 같은 종목 상세를 여는 시나리오의 KIS 호출 절감")
    void measuresCallReductionForRealisticStockDetailTraffic() {
        int viewers = 30;
        // 캐시가 없을 때의 호출 수는 1회 조회의 구성으로 결정된다:
        // getBasicInfo(현재가 1) + getFinancials(손익·재무비율·안정성 3 + 현재가 1) = 5회/조회.
        int callsPerViewWithoutCache = 5;
        int baseline = viewers * callsPerViewWithoutCache;

        for (int i = 0; i < viewers; i++) {
            companyInfoService.getBasicInfo("005930");
            companyInfoService.getFinancials("005930");
        }

        int actual = transport.calls();
        double reduction = 100.0 * (baseline - actual) / baseline;
        System.out.printf("[측정] 종목 상세 %d회 조회: 캐시 없음 %d회 → 캐시 적용 %d회 (%.1f%% 절감)%n",
                viewers, baseline, actual, reduction);

        // 종목당 고유 데이터는 4종(현재가 + 재무 3종)뿐이므로 조회자 수와 무관하게 4회로 수렴한다.
        assertThat(actual).isEqualTo(4);
        assertThat(reduction).isGreaterThan(97.0);
    }

    @Test
    @DisplayName("(c-8) 주문 같은 POST 와 잔고 조회는 캐시되지 않는다 (매매가 낡은 값을 보면 안 된다)")
    void nonAllowlistedCallsAreNeverCached() {
        callBalance(USER_APP_KEY);
        callBalance(USER_APP_KEY);

        assertThat(transport.calls()).as("잔고 조회는 매번 KIS 로 나가야 한다").isEqualTo(2);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 캐시 allowlist 밖의 GET(잔고조회) — rate limit 만 타는 경로. */
    private void callBalance(String appKey) {
        kisApiClient.get(REAL_BASE_URL, "/uapi/domestic-stock/v1/trading/inquire-balance",
                TR_BALANCE, "TOKEN", appKey, "SECRET", Map.of("CANO", "50000000"), Map.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * KIS 로 나가는 HTTP 를 대신하면서 <b>호출 횟수를 세는</b> 전송 계층.
     * 이 카운터가 이 테스트의 유일한 실측 지표다.
     */
    private static final class CountingKisTransport implements ClientHttpRequestFactory {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile HttpStatus failStatus;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            calls.incrementAndGet();
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            HttpStatus fail = failStatus;
            if (fail != null) {
                request.setResponse(new MockClientHttpResponse(new byte[0], fail));
                return request;
            }
            MockClientHttpResponse response =
                    new MockClientHttpResponse(bodyFor(uri).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        }

        private String bodyFor(URI uri) {
            if (uri.getPath().contains(PRICE_ENDPOINT)) {
                return """
                        {"rt_cd":"0","msg1":"정상","output":{"stck_prpr":"71500","per":"12.34","pbr":"1.05"}}""";
            }
            if (uri.getPath().contains("/finance/")) {
                return """
                        {"rt_cd":"0","msg1":"정상","output":[{"stac_yymm":"202312","sale_account":"1000",\
                        "bsop_prti":"200","thtr_ntin":"150","roe_val":"9.1","lblt_rate":"25.5","crnt_rate":"180.2"}]}""";
            }
            return """
                    {"rt_cd":"0","msg1":"정상","output":{}}""";
        }

        void failWith(HttpStatus status) {
            this.failStatus = status;
        }

        void reset() {
            calls.set(0);
            failStatus = null;
        }

        int calls() {
            return calls.get();
        }
    }
}
