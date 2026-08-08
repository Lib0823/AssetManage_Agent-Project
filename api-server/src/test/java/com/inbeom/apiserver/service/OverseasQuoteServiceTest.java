package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.dto.overseas.OverseasOrderbookResponse;
import com.inbeom.apiserver.dto.overseas.OverseasPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasQuoteService 단위 테스트")
class OverseasQuoteServiceTest {

    @Mock
    private KisQuoteService kisQuoteService;

    @Mock
    private KisApiClient kisApiClient;

    @InjectMocks
    private OverseasQuoteService overseasQuoteService;

    private static final String REAL_BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final String SYMBOL = "AAPL";

    private String quoteToken;

    @BeforeEach
    void setUp() {
        quoteToken = "QUOTE_TOKEN";
    }

    private void stubQuoteEnabled() {
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(quoteToken);
        when(kisQuoteService.getQuoteBaseUrl()).thenReturn(REAL_BASE_URL);
        when(kisQuoteService.getQuoteAppKey()).thenReturn("QUOTE_APP_KEY");
        when(kisQuoteService.getQuoteAppSecret()).thenReturn("QUOTE_APP_SECRET");
    }

    private ResponseEntity<Map> body(Map<String, Object> map) {
        return new ResponseEntity<>(map, HttpStatus.OK);
    }

    private Map<String, Object> okBody(String outputKey, Object output) {
        Map<String, Object> map = new HashMap<>();
        map.put("rt_cd", "0");
        map.put(outputKey, output);
        return map;
    }

    // ─── getPrice ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPrice - 현재가상세(HHDFS76200200)를 실전 시세 도메인으로 호출하고 가격을 매핑한다")
    void getPrice_Success() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> output = Map.of(
                "last", "195.50",
                "base", "190.00",
                "diff", "5.50",
                "rate", "2.89",
                "curr", "USD"
        );
        when(kisApiClient.get(
                eq(REAL_BASE_URL),
                eq("/uapi/overseas-price/v1/quotations/price-detail"),
                eq("HHDFS76200200"),
                eq(quoteToken),
                eq("QUOTE_APP_KEY"),
                eq("QUOTE_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(body(okBody("output", output)));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getSymbol()).isEqualTo(SYMBOL);
        assertThat(result.getExchange()).isEqualTo("NASD");
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getLast()).isEqualByComparingTo("195.50");
        assertThat(result.getBase()).isEqualByComparingTo("190.00");
        assertThat(result.getDiff()).isEqualByComparingTo("5.50");
        assertThat(result.getRate()).isEqualByComparingTo("2.89");
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getPrice - EXCD 는 잔고코드가 아니라 시세코드(NYS)로 보낸다")
    void getPrice_SendsQuoteCodeAsExcd() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output", Map.of("last", "100"))));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NYSE");

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                paramsCaptor.capture(), eq(Map.class));
        assertThat(paramsCaptor.getValue())
                .containsEntry("EXCD", "NYS")
                .containsEntry("SYMB", SYMBOL)
                .containsEntry("AUTH", "");
        // 응답 exchange 는 잔고코드로 되돌린다.
        assertThat(result.getExchange()).isEqualTo("NYSE");
    }

    @Test
    @DisplayName("getPrice - diff/rate 가 비어 오면 last/base 로 직접 도출한다")
    void getPrice_DerivesDiffAndRateWhenMissing() {
        // Given: KIS 가 diff/rate 를 빈 문자열로 내려주는 경우
        stubQuoteEnabled();
        Map<String, Object> output = new HashMap<>();
        output.put("last", "210");
        output.put("base", "200");
        output.put("diff", "");
        output.put("rate", "  ");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(okBody("output", output)));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getDiff()).isEqualByComparingTo("10");
        assertThat(result.getRate()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("getPrice - base 가 0 이면 diff/rate 를 도출하지 않는다 (0 나눗셈 방지)")
    void getPrice_ZeroBase_SkipsDerivation() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> output = new HashMap<>();
        output.put("last", "210");
        output.put("base", "0");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(okBody("output", output)));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getLast()).isEqualByComparingTo("210");
        assertThat(result.getDiff()).isNull();
        assertThat(result.getRate()).isNull();
    }

    @Test
    @DisplayName("getPrice - 콤마가 포함된 가격 문자열을 파싱한다")
    void getPrice_ParsesCommaSeparatedNumbers() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output", Map.of("last", "1,234.56"))));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getLast()).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("getPrice - curr 가 없으면 거래소 기본 통화(USD)를 쓴다")
    void getPrice_MissingCurrency_UsesExchangeDefault() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output", Map.of("last", "100"))));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "AMEX");

        // Then
        assertThat(result.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("getPrice - 시세 키 미설정이면 KIS 호출 없이 '키 필요' notice 로 degrade")
    void getPrice_QuoteDisabled_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getLast()).isNull();
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE);
        assertThat(result.getExchange()).isEqualTo("NASD");
        assertThat(result.getCurrency()).isEqualTo("USD");
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getPrice - 토큰 획득 실패 시 KIS 호출 없이 '키 필요' notice 로 degrade")
    void getPrice_TokenNull_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(null);

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getPrice - rt_cd != 0 이면 '조회 실패' notice 로 degrade")
    void getPrice_RtCdNotZero_Degrades() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> failed = new HashMap<>();
        failed.put("rt_cd", "1");
        failed.put("msg1", "해외시세 권한이 없습니다");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failed));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getLast()).isNull();
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getPrice - output 이 없으면 '조회 실패' notice 로 degrade")
    void getPrice_MissingOutput_Degrades() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> noOutput = new HashMap<>();
        noOutput.put("rt_cd", "0");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(noOutput));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getPrice - last 가 비어있으면 데이터 없음으로 보고 degrade")
    void getPrice_EmptyLast_Degrades() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> output = new HashMap<>();
        output.put("last", "");
        output.put("base", "190.00");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(okBody("output", output)));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getLast()).isNull();
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getPrice - KIS 호출이 예외를 던져도 전파하지 않고 degrade")
    void getPrice_ExceptionThrown_Degrades() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenThrow(new ResourceAccessException("read timed out"));

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getPrice - 미지원 거래소 코드는 NASD 로 폴백한다")
    void getPrice_UnknownExchange_FallsBackToNasd() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        OverseasPriceResponse result = overseasQuoteService.getPrice(SYMBOL, "HKEX");

        // Then
        assertThat(result.getExchange()).isEqualTo("NASD");
    }

    // ─── getOverseasOrderbook ───────────────────────────────────────────────

    @Test
    @DisplayName("getOverseasOrderbook - 1호가(HHDFS76200100)를 조회해 매도/매수 1단계를 채운다")
    void getOverseasOrderbook_Success() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> output1 = Map.of(
                "pask1", "195.60",
                "pbid1", "195.40",
                "vask1", "300",
                "vbid1", "250"
        );
        when(kisApiClient.get(
                eq(REAL_BASE_URL),
                eq("/uapi/overseas-price/v1/quotations/inquire-asking-price"),
                eq("HHDFS76200100"),
                eq(quoteToken),
                eq("QUOTE_APP_KEY"),
                eq("QUOTE_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(body(okBody("output1", output1)));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getAsks()).hasSize(1);
        assertThat(result.getAsks().get(0).getPrice()).isEqualByComparingTo("195.60");
        assertThat(result.getAsks().get(0).getQuantity()).isEqualTo(300);
        assertThat(result.getBids()).hasSize(1);
        assertThat(result.getBids().get(0).getPrice()).isEqualByComparingTo("195.40");
        assertThat(result.getBids().get(0).getQuantity()).isEqualTo(250);
    }

    @Test
    @DisplayName("getOverseasOrderbook - 호가가 output(output1 아님)에 와도 탐색해서 찾는다")
    void getOverseasOrderbook_FindsOutputVariant() {
        // Given: 응답 위치가 output1/output2/output 로 달라질 수 있어 순서대로 탐색한다.
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output", Map.of("pask1", "10.5", "pbid1", "10.4"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getAsks().get(0).getPrice()).isEqualByComparingTo("10.5");
    }

    @Test
    @DisplayName("getOverseasOrderbook - 잔량이 없으면 0 으로 채운다")
    void getOverseasOrderbook_MissingQuantity_DefaultsToZero() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("pask1", "195.60", "pbid1", "195.40"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getAsks().get(0).getQuantity()).isZero();
        assertThat(result.getBids().get(0).getQuantity()).isZero();
    }

    @Test
    @DisplayName("getOverseasOrderbook - 매도만 있으면 매수 목록은 비어있다")
    void getOverseasOrderbook_OnlyAsk_LeavesBidsEmpty() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("pask1", "195.60", "vask1", "100"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getAsks()).hasSize(1);
        assertThat(result.getBids()).isEmpty();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getOverseasOrderbook - 시세 미연동이면 빈 호가 + '키 필요' notice")
    void getOverseasOrderbook_QuoteDisabled_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getAsks()).isEmpty();
        assertThat(result.getBids()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getOverseasOrderbook - 토큰 획득 실패 시 빈 호가 + '키 필요' notice")
    void getOverseasOrderbook_TokenNull_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(null);

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getOverseasOrderbook - rt_cd != 0 이면 '조회 실패' notice")
    void getOverseasOrderbook_RtCdNotZero_Degrades() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> failed = new HashMap<>();
        failed.put("rt_cd", "1");
        failed.put("msg1", "권한 없음");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failed));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
        assertThat(result.getAsks()).isEmpty();
    }

    @Test
    @DisplayName("getOverseasOrderbook - 1호가 필드가 전혀 없으면 '조회 실패' notice")
    void getOverseasOrderbook_MissingLevels_Degrades() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("some_other_field", "1"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getOverseasOrderbook - 호가 값이 숫자가 아니면 빈 호가로 degrade")
    void getOverseasOrderbook_UnparseablePrices_Degrades() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("pask1", "N/A", "pbid1", "N/A"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getAsks()).isEmpty();
        assertThat(result.getBids()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
    }

    @Test
    @DisplayName("getOverseasOrderbook - KIS 호출 예외 시 빈 호가로 degrade")
    void getOverseasOrderbook_ExceptionThrown_Degrades() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenThrow(new ResourceAccessException("connect timed out"));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getNotice()).isEqualTo(OverseasQuoteService.NOTICE_OVERSEAS_QUOTE_FAILED);
        assertThat(result.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("getOverseasOrderbook - EXCD 는 시세코드(AMS)로 보낸다")
    void getOverseasOrderbook_SendsQuoteCodeAsExcd() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("pask1", "5.5", "pbid1", "5.4"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "AMEX");

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                paramsCaptor.capture(), eq(Map.class));
        assertThat(paramsCaptor.getValue()).containsEntry("EXCD", "AMS");
        assertThat(result.getExchange()).isEqualTo("AMEX");
    }

    @Test
    @DisplayName("BigDecimal 파싱 - 소수점 잔량은 정수로 절삭된다")
    void getOverseasOrderbook_DecimalQuantity_Truncated() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(okBody("output1", Map.of("pask1", "10", "vask1", "3.9"))));

        // When
        OverseasOrderbookResponse result = overseasQuoteService.getOverseasOrderbook(SYMBOL, "NASD");

        // Then
        assertThat(result.getAsks().get(0).getQuantity()).isEqualTo(3);
        assertThat(result.getAsks().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("10"));
    }
}
