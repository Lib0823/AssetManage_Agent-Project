package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.kis.KisTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisQuoteService 단위 테스트")
class KisQuoteServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private KisQuoteService kisQuoteService;

    private static final String REAL_BASE_URL = "https://openapi.koreainvestment.com:9443";

    @BeforeEach
    void setUp() {
        kisQuoteService = new KisQuoteService();
        ReflectionTestUtils.setField(kisQuoteService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(kisQuoteService, "quoteBaseUrl", REAL_BASE_URL);
        ReflectionTestUtils.setField(kisQuoteService, "quoteAppKey", "QUOTE_APP_KEY");
        ReflectionTestUtils.setField(kisQuoteService, "quoteAppSecret", "QUOTE_APP_SECRET");
        ReflectionTestUtils.setField(kisQuoteService, "tokenCacheTtl", 86400000L);
    }

    private ResponseEntity<KisTokenResponse> tokenResponse(String accessToken, HttpStatus status) {
        KisTokenResponse body = new KisTokenResponse();
        body.setAccessToken(accessToken);
        return new ResponseEntity<>(body, status);
    }

    @Test
    @DisplayName("isQuoteEnabled - quote app key/secret 이 모두 설정되면 true")
    void isQuoteEnabled_BothConfigured_ReturnsTrue() {
        // Given: setUp 에서 key/secret 모두 설정됨

        // When / Then
        assertThat(kisQuoteService.isQuoteEnabled()).isTrue();
    }

    @Test
    @DisplayName("isQuoteEnabled - app key 가 null/공백이면 false")
    void isQuoteEnabled_BlankAppKey_ReturnsFalse() {
        // Given / When / Then
        ReflectionTestUtils.setField(kisQuoteService, "quoteAppKey", null);
        assertThat(kisQuoteService.isQuoteEnabled()).isFalse();

        ReflectionTestUtils.setField(kisQuoteService, "quoteAppKey", "   ");
        assertThat(kisQuoteService.isQuoteEnabled()).isFalse();
    }

    @Test
    @DisplayName("isQuoteEnabled - app secret 이 null/공백이면 false")
    void isQuoteEnabled_BlankAppSecret_ReturnsFalse() {
        // Given / When / Then
        ReflectionTestUtils.setField(kisQuoteService, "quoteAppSecret", null);
        assertThat(kisQuoteService.isQuoteEnabled()).isFalse();

        ReflectionTestUtils.setField(kisQuoteService, "quoteAppSecret", "");
        assertThat(kisQuoteService.isQuoteEnabled()).isFalse();
    }

    @Test
    @DisplayName("getter - 설정된 실전 시세 도메인/키를 그대로 노출한다")
    void getters_ExposeConfiguredValues() {
        // Given / When / Then
        assertThat(kisQuoteService.getQuoteBaseUrl()).isEqualTo(REAL_BASE_URL);
        assertThat(kisQuoteService.getQuoteAppKey()).isEqualTo("QUOTE_APP_KEY");
        assertThat(kisQuoteService.getQuoteAppSecret()).isEqualTo("QUOTE_APP_SECRET");
    }

    @Test
    @DisplayName("getQuoteAccessToken - 비활성(키 미설정)이면 OAuth 호출 없이 null")
    void getQuoteAccessToken_Disabled_ReturnsNull() {
        // Given
        ReflectionTestUtils.setField(kisQuoteService, "quoteAppKey", "");

        // When
        String token = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(token).isNull();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("getQuoteAccessToken - OAuth 성공 시 access_token 반환")
    void getQuoteAccessToken_Success() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenReturn(tokenResponse("QUOTE_TOKEN", HttpStatus.OK));

        // When
        String token = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(token).isEqualTo("QUOTE_TOKEN");
        verify(restTemplate, times(1)).postForEntity(
                eq(REAL_BASE_URL + "/oauth2/tokenP"), any(), eq(KisTokenResponse.class));
    }

    @Test
    @DisplayName("getQuoteAccessToken - 두 번째 호출은 캐시 히트로 OAuth 를 재요청하지 않는다")
    void getQuoteAccessToken_CacheHit_CallsOAuthOnce() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenReturn(tokenResponse("QUOTE_TOKEN", HttpStatus.OK));

        // When
        String first = kisQuoteService.getQuoteAccessToken();
        String second = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(first).isEqualTo("QUOTE_TOKEN");
        assertThat(second).isEqualTo("QUOTE_TOKEN");
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(KisTokenResponse.class));
    }

    @Test
    @DisplayName("getQuoteAccessToken - 캐시 만료 시 OAuth 를 다시 요청한다")
    void getQuoteAccessToken_CacheExpired_RefetchesToken() {
        // Given: TTL 을 음수로 두면 캐시 엔트리는 생성 즉시 만료 상태다.
        ReflectionTestUtils.setField(kisQuoteService, "tokenCacheTtl", -1000L);
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenReturn(tokenResponse("TOKEN_1", HttpStatus.OK))
                .thenReturn(tokenResponse("TOKEN_2", HttpStatus.OK));

        // When
        String first = kisQuoteService.getQuoteAccessToken();
        String second = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(first).isEqualTo("TOKEN_1");
        assertThat(second).isEqualTo("TOKEN_2");
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(KisTokenResponse.class));
    }

    @Test
    @DisplayName("getQuoteAccessToken - OAuth 응답이 200 이 아니면 null (예외 전파 없음)")
    void getQuoteAccessToken_NonOkStatus_ReturnsNull() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenReturn(tokenResponse("IGNORED", HttpStatus.FORBIDDEN));

        // When
        String token = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(token).isNull();
    }

    @Test
    @DisplayName("getQuoteAccessToken - OAuth 응답 body 가 null 이면 null")
    void getQuoteAccessToken_NullBody_ReturnsNull() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenReturn(new ResponseEntity<>((KisTokenResponse) null, HttpStatus.OK));

        // When
        String token = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(token).isNull();
    }

    @Test
    @DisplayName("getQuoteAccessToken - OAuth 호출이 예외를 던져도 null 로 degrade 한다")
    void getQuoteAccessToken_ExceptionThrown_ReturnsNull() {
        // Given: KIS 도달 불가(타임아웃 등)
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        // When
        String token = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(token).isNull();
    }

    @Test
    @DisplayName("getQuoteAccessToken - 실패한 응답은 캐시되지 않아 다음 호출에서 재시도한다")
    void getQuoteAccessToken_FailureNotCached_RetriesNextCall() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(KisTokenResponse.class)))
                .thenThrow(new ResourceAccessException("connect timed out"))
                .thenReturn(tokenResponse("RECOVERED_TOKEN", HttpStatus.OK));

        // When
        String first = kisQuoteService.getQuoteAccessToken();
        String second = kisQuoteService.getQuoteAccessToken();

        // Then
        assertThat(first).isNull();
        assertThat(second).isEqualTo("RECOVERED_TOKEN");
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(KisTokenResponse.class));
    }
}
