package com.inbeom.apiserver.client;

import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.KisApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("KisApiClient 단위 테스트")
class KisApiClientTest {

    /** 전역 kis.base-url 로 주입되는 실전 매매 도메인 */
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
    /** 호출부가 명시적으로 넘기는 도메인 (예: kis.quote-base-url). 전역값과 구분되도록 다른 값을 쓴다. */
    private static final String EXPLICIT_BASE_URL = "https://openapi-quote.test.local:9443";

    private static final String TOKEN = "ACCESS_TOKEN";
    private static final String APP_KEY = "APP_KEY";
    private static final String APP_SECRET = "APP_SECRET";

    private KisApiClient kisApiClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        kisApiClient = new KisApiClient();
        ReflectionTestUtils.setField(kisApiClient, "kisBaseUrl", BASE_URL);

        // restTemplate 은 필드 초기화로 생성되는 final 필드이므로, 주입이 아니라
        // 실제 인스턴스를 꺼내 MockRestServiceServer 를 바인딩한다.
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(kisApiClient, "restTemplate");
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Nested
    @DisplayName("callKisApi - 요청 조립과 응답 처리")
    class CallKisApi {

        @Test
        @DisplayName("성공 시 인증 헤더와 TR_ID 로 호출하고 응답을 그대로 반환한다")
        @SuppressWarnings("rawtypes")
        void success_SendsAuthHeadersAndTrId() {
            // Given: 명시한 도메인으로 호출되고 TR_ID 는 그대로 전송된다
            mockServer.expect(requestTo(EXPLICIT_BASE_URL + "/uapi/domestic-stock/v1/trading/inquire-balance"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header("authorization", "Bearer " + TOKEN))
                    .andExpect(header("appkey", APP_KEY))
                    .andExpect(header("appsecret", APP_SECRET))
                    .andExpect(header("tr_id", "TTTC8434R"))
                    .andExpect(header("custtype", "P"))
                    .andRespond(withSuccess("{\"rt_cd\":\"0\",\"msg1\":\"정상처리\"}", MediaType.APPLICATION_JSON));

            // When
            ResponseEntity<Map> response = kisApiClient.callKisApi(
                    EXPLICIT_BASE_URL,
                    "/uapi/domestic-stock/v1/trading/inquire-balance",
                    HttpMethod.GET,
                    "TTTC8434R", TOKEN, APP_KEY, APP_SECRET, null, Map.class);

            // Then
            mockServer.verify();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("rt_cd", "0");
        }

        @Test
        @DisplayName("baseUrl 이 null/공백이면 전역 kis.base-url 을 사용한다")
        @SuppressWarnings("rawtypes")
        void blankBaseUrl_FallsBackToGlobalBaseUrl() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/ping"))
                    .andExpect(header("tr_id", "TTTC8434R"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.callKisApi("   ", "/ping", HttpMethod.GET,
                    "TTTC8434R", TOKEN, APP_KEY, APP_SECRET, null, Map.class);

            // Then
            mockServer.verify();
        }

        @Test
        @DisplayName("baseUrl 없는 오버로드는 전역 kis.base-url 로 호출한다")
        @SuppressWarnings("rawtypes")
        void overloadWithoutBaseUrl_UsesGlobalBaseUrl() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/ping"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.callKisApi("/ping", HttpMethod.GET,
                    "FHKST01010100", TOKEN, APP_KEY, APP_SECRET, null, Map.class);

            // Then
            mockServer.verify();
        }

        @Test
        @DisplayName("POST 는 JSON Content-Type 과 함께 본문을 전송한다")
        @SuppressWarnings("rawtypes")
        void post_SendsJsonBody() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/order"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(jsonPath("$.PDNO").value("005930"))
                    .andExpect(jsonPath("$.ORD_QTY").value("10"))
                    .andRespond(withSuccess("{\"rt_cd\":\"0\"}", MediaType.APPLICATION_JSON));

            // When
            Map<String, String> body = new LinkedHashMap<>();
            body.put("PDNO", "005930");
            body.put("ORD_QTY", "10");
            ResponseEntity<Map> response = kisApiClient.callKisApi(
                    BASE_URL, "/order", HttpMethod.POST,
                    "TTTC0802U", TOKEN, APP_KEY, APP_SECRET, body, Map.class);

            // Then
            mockServer.verify();
            assertThat(response.getBody()).containsEntry("rt_cd", "0");
        }

        @Test
        @DisplayName("4xx 응답은 상태·본문을 보존한 KIS_API_CLIENT_ERROR 로 변환한다")
        @SuppressWarnings("rawtypes")
        void httpClientError_ThrowsClientError() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/order"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"rt_cd\":\"1\",\"msg1\":\"주문가능금액이 부족합니다\"}"));

            // When / Then
            assertThatThrownBy(() -> kisApiClient.callKisApi(
                    BASE_URL, "/order", HttpMethod.POST,
                    "TTTC0802U", TOKEN, APP_KEY, APP_SECRET, Map.of("a", "b"), Map.class))
                    .isInstanceOf(KisApiException.class)
                    .hasMessageContaining("KIS API error (HTTP 400)")
                    .hasMessageContaining("주문가능금액이 부족합니다")
                    .extracting(e -> ((KisApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KIS_API_CLIENT_ERROR);
        }

        @Test
        @DisplayName("5xx 응답은 KIS_API_SERVER_ERROR 로 변환한다")
        @SuppressWarnings("rawtypes")
        void httpServerError_ThrowsServerError() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/quote"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"msg1\":\"서버 점검중\"}"));

            // When / Then
            assertThatThrownBy(() -> kisApiClient.callKisApi(
                    BASE_URL, "/quote", HttpMethod.GET,
                    "FHKST01010100", TOKEN, APP_KEY, APP_SECRET, null, Map.class))
                    .isInstanceOf(KisApiException.class)
                    .hasMessageContaining("KIS API error (HTTP 500)")
                    .hasMessageContaining("서버 점검중")
                    .extracting(e -> ((KisApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KIS_API_SERVER_ERROR);
        }

        @Test
        @DisplayName("연결 실패/타임아웃은 KIS_API_NETWORK_ERROR 로 변환한다")
        @SuppressWarnings("rawtypes")
        void networkFailure_ThrowsNetworkError() {
            // Given: RestTemplate 은 IOException 을 ResourceAccessException 으로 감싼다
            mockServer.expect(requestTo(BASE_URL + "/quote"))
                    .andRespond(withException(new IOException("Read timed out")));

            // When / Then
            assertThatThrownBy(() -> kisApiClient.callKisApi(
                    BASE_URL, "/quote", HttpMethod.GET,
                    "FHKST01010100", TOKEN, APP_KEY, APP_SECRET, null, Map.class))
                    .isInstanceOf(KisApiException.class)
                    .hasMessageContaining("KIS API network error")
                    .hasMessageContaining("Read timed out")
                    .extracting(e -> ((KisApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KIS_API_NETWORK_ERROR);
        }

        @Test
        @DisplayName("응답 본문이 파싱 불가한 형식이면 KIS_API_SERVER_ERROR 로 변환한다")
        @SuppressWarnings("rawtypes")
        void malformedResponseBody_ThrowsServerError() {
            // Given: JSON 이라 선언했지만 실제로는 파싱 불가한 본문
            mockServer.expect(requestTo(BASE_URL + "/quote"))
                    .andRespond(withSuccess("<<not-json>>", MediaType.APPLICATION_JSON));

            // When / Then
            assertThatThrownBy(() -> kisApiClient.callKisApi(
                    BASE_URL, "/quote", HttpMethod.GET,
                    "FHKST01010100", TOKEN, APP_KEY, APP_SECRET, null, Map.class))
                    .isInstanceOf(KisApiException.class)
                    .hasMessageContaining("KIS API call failed")
                    .extracting(e -> ((KisApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KIS_API_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("get - 쿼리스트링 조립")
    class Get {

        @Test
        @DisplayName("queryParams 를 key=value&... 로 이어붙이고 마지막 & 를 제거한다")
        @SuppressWarnings("rawtypes")
        void multipleParams_JoinedWithAmpersand() {
            // Given
            mockServer.expect(requestTo(EXPLICIT_BASE_URL
                            + "/uapi/domestic-stock/v1/quotations/inquire-price"
                            + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header("tr_id", "FHKST01010100"))
                    .andRespond(withSuccess("{\"rt_cd\":\"0\"}", MediaType.APPLICATION_JSON));

            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "J");
            params.put("FID_INPUT_ISCD", "005930");

            // When
            ResponseEntity<Map> response = kisApiClient.get(
                    EXPLICIT_BASE_URL, "/uapi/domestic-stock/v1/quotations/inquire-price",
                    "FHKST01010100", TOKEN, APP_KEY, APP_SECRET, params, Map.class);

            // Then
            mockServer.verify();
            assertThat(response.getBody()).containsEntry("rt_cd", "0");
        }

        @Test
        @DisplayName("queryParams 가 null 이면 endpoint 를 그대로 사용한다")
        @SuppressWarnings("rawtypes")
        void nullParams_NoQueryString() {
            // Given
            mockServer.expect(requestTo(EXPLICIT_BASE_URL + "/quote"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.get(EXPLICIT_BASE_URL, "/quote", "FHKST01010100",
                    TOKEN, APP_KEY, APP_SECRET, null, Map.class);

            // Then
            mockServer.verify();
        }

        @Test
        @DisplayName("queryParams 가 빈 Map 이면 '?' 를 붙이지 않는다")
        @SuppressWarnings("rawtypes")
        void emptyParams_NoQuestionMark() {
            // Given
            mockServer.expect(requestTo(EXPLICIT_BASE_URL + "/quote"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.get(EXPLICIT_BASE_URL, "/quote", "FHKST01010100",
                    TOKEN, APP_KEY, APP_SECRET, Map.of(), Map.class);

            // Then
            mockServer.verify();
        }

        @Test
        @DisplayName("baseUrl 없는 오버로드는 전역 kis.base-url 로 호출한다")
        @SuppressWarnings("rawtypes")
        void overloadWithoutBaseUrl_UsesGlobalBaseUrl() {
            // Given: 전역 도메인으로 호출된다
            mockServer.expect(requestTo(BASE_URL + "/balance?CANO=12345678"))
                    .andExpect(header("tr_id", "TTTC8434R"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.get("/balance", "TTTC8434R", TOKEN, APP_KEY, APP_SECRET,
                    Map.of("CANO", "12345678"), Map.class);

            // Then
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("post - 오버로드별 도메인 선택")
    class Post {

        @Test
        @DisplayName("baseUrl 없는 오버로드는 전역 kis.base-url 로 POST 한다")
        @SuppressWarnings("rawtypes")
        void overloadWithoutBaseUrl_UsesGlobalBaseUrl() {
            // Given
            mockServer.expect(requestTo(BASE_URL + "/order"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("tr_id", "TTTC0802U"))
                    .andExpect(jsonPath("$.PDNO").value("005930"))
                    .andRespond(withSuccess("{\"rt_cd\":\"0\"}", MediaType.APPLICATION_JSON));

            // When
            ResponseEntity<Map> response = kisApiClient.post(
                    "/order", "TTTC0802U", TOKEN, APP_KEY, APP_SECRET,
                    Map.of("PDNO", "005930"), Map.class);

            // Then
            mockServer.verify();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("명시한 baseUrl 로 POST 한다")
        @SuppressWarnings("rawtypes")
        void explicitBaseUrl_UsesGivenDomain() {
            // Given: 명시한 도메인으로 POST 된다
            mockServer.expect(requestTo(EXPLICIT_BASE_URL + "/order"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("tr_id", "TTTC0802U"))
                    .andRespond(withSuccess("{\"rt_cd\":\"0\"}", MediaType.APPLICATION_JSON));

            // When
            kisApiClient.post(EXPLICIT_BASE_URL, "/order", "TTTC0802U",
                    TOKEN, APP_KEY, APP_SECRET, Map.of("PDNO", "005930"), Map.class);

            // Then
            mockServer.verify();
        }
    }
}
