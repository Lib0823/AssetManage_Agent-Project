package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisQuoteClient 단위 테스트")
class KisQuoteClientTest {

    @Mock
    private KisQuoteService kisQuoteService;

    @Mock
    private KisApiClient kisApiClient;

    @InjectMocks
    private KisQuoteClient kisQuoteClient;

    private static final String REAL_BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final String STOCK_CODE = "005930";

    private String quoteToken;

    @BeforeEach
    void setUp() {
        quoteToken = "QUOTE_TOKEN";
    }

    /**
     * 시세 자격증명이 정상 설정되고 토큰도 획득되는 상태로 스텁한다.
     */
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

    @Test
    @DisplayName("isEnabled - KisQuoteService.isQuoteEnabled 에 위임한다")
    void isEnabled_DelegatesToQuoteService() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);

        // When / Then
        assertThat(kisQuoteClient.isEnabled()).isTrue();
        verify(kisQuoteService, times(1)).isQuoteEnabled();
    }

    @Test
    @DisplayName("getNotice - 연동 정상이면 null, 미연동이면 키 필요 안내")
    void getNotice_DependsOnEnabled() {
        // Given / When / Then
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        assertThat(kisQuoteClient.getNotice()).isNull();

        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);
        assertThat(kisQuoteClient.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
    }

    @Test
    @DisplayName("unavailableNotice - 키가 있으면 '점검', 키가 없으면 '키 필요' 안내로 구분한다")
    void unavailableNotice_DistinguishesKeyMissingFromOutage() {
        // Given: 키는 설정됐지만 조회에 실패한 상황
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);

        // When / Then
        assertThat(kisQuoteClient.unavailableNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);

        // Given: 키 미설정
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // Then
        assertThat(kisQuoteClient.unavailableNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
    }

    @Test
    @DisplayName("unavailableNotice - 우리 쪽 rate limit 거부는 'KIS 점검'이 아니라 별도 문구로 안내한다")
    void unavailableNotice_RateLimitedUsesOwnMessage() {
        // Given: 키는 정상. 실패 원인이 자체 토큰 버킷일 뿐 KIS 는 멀쩡하다.
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);

        // When / Then
        assertThat(kisQuoteClient.unavailableNotice(true))
                .isEqualTo(KisQuoteClient.NOTICE_KIS_BUSY)
                .isNotEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("fetchCurrentPriceResult - rate limit 거부는 결과에 원인으로 표시된다")
    void fetchCurrentPriceResult_RateLimited_IsReported() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException("KIS 호출 한도를 초과해 요청을 보내지 않았습니다"));

        // When
        KisQuoteClient.QuoteResult result = kisQuoteClient.fetchCurrentPriceResult(STOCK_CODE);

        // Then: 값은 없지만 원인이 "KIS 장애"가 아니라 "우리 쪽 한도"임이 구분된다.
        assertThat(result.data()).isNull();
        assertThat(result.rateLimited()).isTrue();
    }

    @Test
    @DisplayName("fetchCurrentPrice - 실전 시세 도메인/자격증명으로 FHKST01010100 을 호출하고 output 을 반환")
    void fetchCurrentPrice_Success() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", Map.of("stck_prpr", "70000", "prdy_vrss", "500", "prdy_ctrt", "0.72"));
        when(kisApiClient.get(
                eq(REAL_BASE_URL),
                eq("/uapi/domestic-stock/v1/quotations/inquire-price"),
                eq("FHKST01010100"),
                eq(quoteToken),
                eq("QUOTE_APP_KEY"),
                eq("QUOTE_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("stck_prpr")).isEqualTo("70000");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                paramsCaptor.capture(), eq(Map.class));
        assertThat(paramsCaptor.getValue())
                .containsEntry("FID_COND_MRKT_DIV_CODE", "J")
                .containsEntry("FID_INPUT_ISCD", STOCK_CODE);
    }

    @Test
    @DisplayName("fetchCurrentPrice - 시세 미연동(키 미설정)이면 KIS 호출 없이 null")
    void fetchCurrentPrice_QuoteDisabled_ReturnsNull() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("fetchCurrentPrice - 토큰 획득 실패 시 KIS 호출 없이 null")
    void fetchCurrentPrice_TokenNull_ReturnsNull() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(null);

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("fetchCurrentPrice - rt_cd != 0 이면 null 로 degrade")
    void fetchCurrentPrice_RtCdNotZero_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "1");
        response.put("msg1", "조회할 자료가 없습니다");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCurrentPrice - 응답 body 가 null 이어도 예외 없이 null")
    void fetchCurrentPrice_NullBody_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(new ResponseEntity<>((Map<String, Object>) null, HttpStatus.OK));

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCurrentPrice - rt_cd=0 이지만 output 이 Map 이 아니면 null")
    void fetchCurrentPrice_OutputNotMap_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", "not-a-map");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCurrentPrice - KIS 호출이 예외를 던져도 전파하지 않고 null")
    void fetchCurrentPrice_ExceptionThrown_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenThrow(new ResourceAccessException("read timed out"));

        // When
        Map<String, Object> result = kisQuoteClient.fetchCurrentPrice(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchOrderbook - FHKST01010200 을 호출하고 output1(호가)을 반환")
    void fetchOrderbook_Success() {
        // Given: 호가는 output 이 아니라 output1 에 담겨 온다.
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output1", Map.of("askp1", "70100", "bidp1", "70000", "askp_rsqn1", "1200"));
        response.put("output2", Map.of("antc_cnpr", "70050"));
        when(kisApiClient.get(
                eq(REAL_BASE_URL),
                eq("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn"),
                eq("FHKST01010200"),
                eq(quoteToken),
                eq("QUOTE_APP_KEY"),
                eq("QUOTE_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchOrderbook(STOCK_CODE);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("askp1")).isEqualTo("70100");
        assertThat(result.get("bidp1")).isEqualTo("70000");
    }

    @Test
    @DisplayName("fetchOrderbook - 시세 미연동이면 KIS 호출 없이 null")
    void fetchOrderbook_QuoteDisabled_ReturnsNull() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);

        // When
        Map<String, Object> result = kisQuoteClient.fetchOrderbook(STOCK_CODE);

        // Then
        assertThat(result).isNull();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("fetchOrderbook - rt_cd != 0 이면 null 로 degrade")
    void fetchOrderbook_RtCdNotZero_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "1");
        response.put("msg1", "호가 조회 실패");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchOrderbook(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchOrderbook - output1 이 없으면(output 만 있으면) null")
    void fetchOrderbook_MissingOutput1_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", Map.of("askp1", "70100"));
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        Map<String, Object> result = kisQuoteClient.fetchOrderbook(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchOrderbook - KIS 호출 예외 시 null")
    void fetchOrderbook_ExceptionThrown_ReturnsNull() {
        // Given
        stubQuoteEnabled();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenThrow(new ResourceAccessException("connect timed out"));

        // When
        Map<String, Object> result = kisQuoteClient.fetchOrderbook(STOCK_CODE);

        // Then
        assertThat(result).isNull();
    }
}
