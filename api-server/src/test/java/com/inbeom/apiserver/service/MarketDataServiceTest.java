package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.dto.market.ExchangeRateResponse;
import com.inbeom.apiserver.dto.market.IndicesResponse;
import com.inbeom.apiserver.dto.market.NewsItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketDataService 단위 테스트")
class MarketDataServiceTest {

    @Mock
    private KisQuoteService kisQuoteService;

    @Mock
    private KisApiClient kisApiClient;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private MarketDataService marketDataService;

    private static final String INDEX_ENDPOINT =
            "/uapi/domestic-stock/v1/quotations/inquire-index-price";
    private static final String OVERSEAS_INDEX_ENDPOINT =
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";

    @BeforeEach
    void setUp() {
        // restTemplate 은 필드 초기화로 생성되므로(생성자 주입 아님) 목으로 교체한다.
        ReflectionTestUtils.setField(marketDataService, "restTemplate", restTemplate);
    }

    private void enableQuote() {
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn("QUOTE_TOKEN");
        when(kisQuoteService.getQuoteBaseUrl()).thenReturn("https://openapi.koreainvestment.com:9443");
        when(kisQuoteService.getQuoteAppKey()).thenReturn("QUOTE_APP_KEY");
        when(kisQuoteService.getQuoteAppSecret()).thenReturn("QUOTE_APP_SECRET");
    }

    private ResponseEntity<Map> domesticIndexOk(String value, String change, String pct) {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output1", Map.of(
                "bstp_nmix_prpr", value,
                "bstp_nmix_prdy_vrss", change,
                "bstp_nmix_prdy_ctrt", pct));
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    private ResponseEntity<Map> kisError() {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "권한이 없습니다");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // getIndices
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getIndices - 국내 4개 지수 조회 성공, 해외 실패 시 카테고리 미추가")
    void getIndices_DomesticSuccess_OverseasSkipped() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), eq(INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(domesticIndexOk("2612.43", "18.22", "0.70"));
        when(kisApiClient.get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisError());

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then
        assertThat(result.getCategories()).hasSize(1);
        IndicesResponse.IndexCategory domestic = result.getCategories().get(0);
        assertThat(domestic.getKey()).isEqualTo("domestic");
        assertThat(domestic.getLabel()).isEqualTo("주식(국내)");
        assertThat(domestic.getItems()).hasSize(4);
        assertThat(domestic.getItems()).extracting(IndicesResponse.IndexItem::getLabel)
                .containsExactly("코스피", "코스닥", "코스피200", "KRX300");
        assertThat(domestic.getItems().get(0).getValue()).isEqualByComparingTo(new BigDecimal("2612.43"));
        assertThat(domestic.getItems().get(0).getChange()).isEqualByComparingTo(new BigDecimal("18.22"));
        assertThat(domestic.getItems().get(0).getChangePercent()).isEqualByComparingTo(new BigDecimal("0.70"));

        // 해외는 첫 호출 실패 시 나머지를 건너뛴다 (타임아웃 누적 방지)
        verify(kisApiClient, times(1)).get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getIndices - 해외 지수 성공 시 overseas 카테고리 추가")
    void getIndices_OverseasSuccess_AddsCategory() {
        // Given
        enableQuote();
        Map<String, Object> overseasBody = new HashMap<>();
        overseasBody.put("rt_cd", "0");
        overseasBody.put("output1", Map.of(
                "ovrs_nmix_prpr", "40120.55",
                "ovrs_nmix_prdy_vrss", "120.30",
                "prdy_ctrt", "0.30"));

        when(kisApiClient.get(anyString(), eq(INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(domesticIndexOk("2612.43", "18.22", "0.70"));
        when(kisApiClient.get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(overseasBody, HttpStatus.OK));

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then
        assertThat(result.getCategories()).hasSize(2);
        IndicesResponse.IndexCategory overseas = result.getCategories().get(1);
        assertThat(overseas.getKey()).isEqualTo("overseas");
        assertThat(overseas.getItems()).extracting(IndicesResponse.IndexItem::getLabel)
                .containsExactly("다우존스", "나스닥", "S&P500");
        assertThat(overseas.getItems().get(0).getValue()).isEqualByComparingTo(new BigDecimal("40120.55"));
    }

    @Test
    @DisplayName("getIndices - 해외 output1 이 비면 output2 캔들 종가로 값/전일대비 도출")
    void getIndices_OverseasDerivesFromCandles() {
        // Given
        enableQuote();
        Map<String, Object> overseasBody = new HashMap<>();
        overseasBody.put("rt_cd", "0");
        overseasBody.put("output2", List.of(
                Map.of("ovrs_nmix_prpr", "110"),
                Map.of("ovrs_nmix_prpr", "100")));

        when(kisApiClient.get(anyString(), eq(INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisError());
        when(kisApiClient.get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(overseasBody, HttpStatus.OK));

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then - 국내는 첫 호출 실패로 빈 목록, 해외만 채워짐
        assertThat(result.getCategories()).hasSize(2);
        IndicesResponse.IndexItem dow = result.getCategories().get(1).getItems().get(0);
        assertThat(dow.getValue()).isEqualByComparingTo(new BigDecimal("110"));
        assertThat(dow.getChange()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(dow.getChangePercent()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("getIndices - quote 비활성 시 KIS 호출 없이 빈 국내 카테고리")
    void getIndices_QuoteDisabled_ReturnsEmptyDomestic() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then
        assertThat(result.getCategories()).hasSize(1);
        assertThat(result.getCategories().get(0).getKey()).isEqualTo("domestic");
        assertThat(result.getCategories().get(0).getItems()).isEmpty();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getIndices - 토큰 획득 실패 시 KIS 호출 없이 degrade")
    void getIndices_TokenUnavailable_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(null);

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then
        assertThat(result.getCategories().get(0).getItems()).isEmpty();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getIndices - 첫 국내 지수 호출 실패 시 나머지 3건을 건너뜀")
    void getIndices_FirstDomesticFails_SkipsRemaining() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisError());

        // When
        marketDataService.getIndices();

        // Then - 국내 1회 + 해외 1회만 호출
        verify(kisApiClient, times(1)).get(anyString(), eq(INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
        verify(kisApiClient, times(1)).get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getIndices - 60초 TTL 캐시 적중 시 KIS 재호출 없음")
    void getIndices_UsesCacheWithinTtl() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), eq(INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(domesticIndexOk("2612.43", "18.22", "0.70"));
        when(kisApiClient.get(anyString(), eq(OVERSEAS_INDEX_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisError());

        // When
        IndicesResponse first = marketDataService.getIndices();
        IndicesResponse second = marketDataService.getIndices();

        // Then - 동일 인스턴스 반환, KIS 는 첫 호출에서만 사용 (국내 4 + 해외 1)
        assertThat(second).isSameAs(first);
        verify(kisApiClient, times(5)).get(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getIndices - KIS 호출 예외 시 예외 전파 없이 빈 목록")
    void getIndices_KisThrows_DoesNotPropagate() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenThrow(new RuntimeException("KIS unreachable"));

        // When
        IndicesResponse result = marketDataService.getIndices();

        // Then
        assertThat(result.getCategories()).hasSize(1);
        assertThat(result.getCategories().get(0).getItems()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // getExchangeRates
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> fxTimeseries(Map<String, Object> rates) {
        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    @Test
    @DisplayName("getExchangeRates - timeseries 로 rate/change/changePercent/history 산출")
    void getExchangeRates_Success() {
        // Given - 날짜 오름차순 정렬 후 마지막 값이 rate, 직전 값 대비 변동 계산
        Map<String, Object> rates = new HashMap<>();
        rates.put("2026-07-29", Map.of("KRW", 1300.0));
        rates.put("2026-07-30", Map.of("KRW", 1350.0));
        rates.put("2026-07-31", Map.of("KRW", 1400.0));  // 오름차순 마지막
        when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenReturn(fxTimeseries(rates));

        // When
        List<ExchangeRateResponse> result = marketDataService.getExchangeRates();

        // Then
        assertThat(result).hasSize(4);
        assertThat(result).extracting(ExchangeRateResponse::getCurrency)
                .containsExactly("USD", "JPY", "EUR", "CNY");

        ExchangeRateResponse usd = result.get(0);
        assertThat(usd.getCountry()).isEqualTo("미국");
        assertThat(usd.getRate()).isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(usd.getChange()).isEqualByComparingTo(new BigDecimal("50.00"));
        // 50 / 1350 * 100 = 3.70%
        assertThat(usd.getChangePercent()).isEqualByComparingTo(new BigDecimal("3.70"));
        assertThat(usd.getHistory()).hasSize(3);
        assertThat(usd.getHistory().get(0)).isEqualByComparingTo(new BigDecimal("1300.00"));

        // JPY 는 100엔 환산
        ExchangeRateResponse jpy = result.get(1);
        assertThat(jpy.getCountry()).isEqualTo("일본");
        assertThat(jpy.getRate()).isEqualByComparingTo(new BigDecimal("140000.00"));
    }

    @Test
    @DisplayName("getExchangeRates - history 는 최근 7건까지만 유지")
    void getExchangeRates_LimitsHistoryToSeven() {
        // Given
        Map<String, Object> rates = new HashMap<>();
        for (int day = 1; day <= 10; day++) {
            rates.put(String.format("2026-07-%02d", day), Map.of("KRW", 1300.0 + day));
        }
        when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenReturn(fxTimeseries(rates));

        // When
        List<ExchangeRateResponse> result = marketDataService.getExchangeRates();

        // Then
        assertThat(result.get(0).getHistory()).hasSize(7);
        assertThat(result.get(0).getHistory().get(6)).isEqualByComparingTo(new BigDecimal("1310.00"));
        assertThat(result.get(0).getRate()).isEqualByComparingTo(new BigDecimal("1310.00"));
    }

    @Test
    @DisplayName("getExchangeRates - timeseries 실패 시 latest 단건으로 rate 만 확보")
    void getExchangeRates_TimeseriesFails_FallsBackToLatest() {
        // Given - 첫 호출(timeseries) 예외, 두 번째 호출(latest) 성공
        Map<String, Object> latestBody = new HashMap<>();
        latestBody.put("rates", Map.of("KRW", 1400.0));
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("frankfurter down"))
                .thenReturn(new ResponseEntity<>(latestBody, HttpStatus.OK));

        // When
        List<ExchangeRateResponse> result = marketDataService.getExchangeRates();

        // Then
        ExchangeRateResponse usd = result.get(0);
        assertThat(usd.getRate()).isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(usd.getChange()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(usd.getChangePercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(usd.getHistory()).isEmpty();
    }

    @Test
    @DisplayName("getExchangeRates - 모든 호출 실패 시 rate null, change 0, history 빈 리스트")
    void getExchangeRates_AllFail_DegradesGracefully() {
        // Given
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("frankfurter down"));

        // When
        List<ExchangeRateResponse> result = marketDataService.getExchangeRates();

        // Then
        assertThat(result).hasSize(4);
        assertThat(result).allSatisfy(rate -> {
            assertThat(rate.getRate()).isNull();
            assertThat(rate.getChange()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rate.getHistory()).isEmpty();
        });
    }

    // ---------------------------------------------------------------------
    // getNews (RSS)
    // ---------------------------------------------------------------------

    private static final String RSS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>경제 뉴스</title>
                <link>https://example.com</link>
                <item>
                  <title>코스피 2600선 회복</title>
                  <link>https://example.com/news/1</link>
                  <description><![CDATA[<p>외국인 순매수에 힘입어 상승</p>]]></description>
                  <guid>news-1</guid>
                  <enclosure url="https://example.com/img/1.jpg" type="image/jpeg"/>
                  <pubDate>Fri, 31 Jul 2026 09:41:00 +0900</pubDate>
                </item>
                <item>
                  <title>원달러 환율 소폭 하락</title>
                  <link>https://example.com/news/2</link>
                  <description>환율 1400원 아래로</description>
                  <guid>news-2</guid>
                  <pubDate>Thu, 30 Jul 2026 18:05:00 +0900</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private ResponseEntity<byte[]> rssResponse(String xml) {
        return new ResponseEntity<>(xml.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
    }

    @Test
    @DisplayName("getNews - RSS 파싱 + pubDate 내림차순 정렬 + 제목 기준 중복 제거")
    void getNews_ParsesSortsAndDedupes() {
        // Given - 3개 피드가 모두 동일한 XML 을 반환 → 제목 dedupe 로 2건만 남는다
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(rssResponse(RSS_XML));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then
        assertThat(result).hasSize(2);
        NewsItemResponse first = result.get(0);
        assertThat(first.getTitle()).isEqualTo("코스피 2600선 회복");
        assertThat(first.getDescription()).isEqualTo("외국인 순매수에 힘입어 상승");
        assertThat(first.getLink()).isEqualTo("https://example.com/news/1");
        assertThat(first.getImage()).isEqualTo("https://example.com/img/1.jpg");
        assertThat(first.getDate()).isEqualTo("2026-07-31 09:41");
        assertThat(first.getSource()).isEqualTo("한국경제");
        assertThat(first.getId()).isNotBlank();

        assertThat(result.get(1).getTitle()).isEqualTo("원달러 환율 소폭 하락");
        assertThat(result.get(1).getDate()).isEqualTo("2026-07-30 18:05");
        assertThat(result.get(1).getImage()).isNull();

        verify(restTemplate, times(3))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    @DisplayName("getNews - 최대 8건까지만 반환")
    void getNews_LimitsToEightItems() {
        // Given
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>");
        for (int i = 1; i <= 12; i++) {
            xml.append("<item><title>뉴스 ").append(i).append("</title>")
                    .append("<link>https://example.com/news/").append(i).append("</link>")
                    .append("<pubDate>Fri, 31 Jul 2026 09:41:00 +0900</pubDate></item>");
        }
        xml.append("</channel></rss>");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(rssResponse(xml.toString()));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then
        assertThat(result).hasSize(8);
    }

    @Test
    @DisplayName("getNews - 피드 호출 실패 시 예외 전파 없이 빈 리스트")
    void getNews_FeedFails_ReturnsEmpty() {
        // Given
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new RuntimeException("feed unreachable"));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getNews - 응답 본문이 비어있으면 빈 리스트")
    void getNews_EmptyBody_ReturnsEmpty() {
        // Given
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], HttpStatus.OK));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getNews - pubDate 파싱 실패 시 date=null 이며 목록 뒤로 정렬")
    void getNews_UnparseablePubDate_SortsLast() {
        // Given
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>날짜 없는 뉴스</title>
                    <link>https://example.com/news/x</link>
                    <pubDate>어제</pubDate>
                  </item>
                  <item>
                    <title>정상 뉴스</title>
                    <link>https://example.com/news/y</link>
                    <pubDate>Fri, 31 Jul 2026 09:41:00 +0900</pubDate>
                  </item>
                </channel></rss>
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(rssResponse(xml));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTitle()).isEqualTo("정상 뉴스");
        assertThat(result.get(1).getTitle()).isEqualTo("날짜 없는 뉴스");
        assertThat(result.get(1).getDate()).isNull();
    }

    @Test
    @DisplayName("getNews - ISO offset 형식 pubDate 도 파싱 (KST 변환)")
    void getNews_ParsesIsoOffsetPubDate() {
        // Given
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>ISO 날짜 뉴스</title>
                    <link>https://example.com/news/iso</link>
                    <pubDate>2026-07-31T00:41:00Z</pubDate>
                  </item>
                </channel></rss>
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(rssResponse(xml));

        // When
        List<NewsItemResponse> result = marketDataService.getNews();

        // Then - UTC 00:41 → KST 09:41
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDate()).isEqualTo("2026-07-31 09:41");
    }
}
