package com.inbeom.apiserver.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("DartApiClient 단위 테스트")
class DartApiClientTest {

    private static final String BASE_URL = "https://opendart.fss.or.kr/api";
    private static final String API_KEY = "TEST_DART_KEY";
    private static final String CORP_CODE_URL = BASE_URL + "/corpCode.xml?crtfc_key=" + API_KEY;

    private DartApiClient dartApiClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        dartApiClient = new DartApiClient();
        ReflectionTestUtils.setField(dartApiClient, "dartApiKey", API_KEY);
        ReflectionTestUtils.setField(dartApiClient, "dartBaseUrl", BASE_URL);

        // restTemplate 은 필드 초기화로 생성되는 final 필드라 주입할 수 없어 인스턴스를 꺼내 바인딩한다.
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(dartApiClient, "restTemplate");
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    }

    /** DART corpCode.xml 과 동일한 구조의 ZIP 을 만든다. */
    private byte[] corpCodeZip(String entryName, String xml) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(xml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SAMPLE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
              <list>
                <corp_code>00126380</corp_code>
                <corp_name>삼성전자</corp_name>
                <stock_code>005930</stock_code>
                <modify_date>20240101</modify_date>
              </list>
              <list>
                <corp_code>00164779</corp_code>
                <corp_name>SK하이닉스</corp_name>
                <stock_code>000660</stock_code>
                <modify_date>20240101</modify_date>
              </list>
              <list>
                <corp_code>00999999</corp_code>
                <corp_name>비상장회사</corp_name>
                <stock_code> </stock_code>
                <modify_date>20240101</modify_date>
              </list>
            </result>
            """;

    @Nested
    @DisplayName("isEnabled - API 키 설정 여부")
    class IsEnabled {

        @Test
        @DisplayName("키가 설정되어 있으면 true")
        void keyPresent_ReturnsTrue() {
            assertThat(dartApiClient.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("키가 null 이거나 공백이면 false")
        void keyMissing_ReturnsFalse() {
            // Given
            ReflectionTestUtils.setField(dartApiClient, "dartApiKey", null);
            assertThat(dartApiClient.isEnabled()).isFalse();

            // Given
            ReflectionTestUtils.setField(dartApiClient, "dartApiKey", "   ");
            assertThat(dartApiClient.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("getCorpCode - corpCode.xml lazy 로딩과 매핑")
    class GetCorpCode {

        @Test
        @DisplayName("ZIP 을 파싱해 stock_code → corp_code 매핑을 반환한다")
        void success_ReturnsMappedCorpCode() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(corpCodeZip("CORPCODE.xml", SAMPLE_XML),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            mockServer.verify();
            assertThat(corpCode).isEqualTo("00126380");
        }

        @Test
        @DisplayName("6자리 미만 종목코드는 0 으로 zero-pad 해 조회한다")
        void shortStockCode_ZeroPadded() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess(corpCodeZip("CORPCODE.xml", SAMPLE_XML),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When: "660" → "000660"
            String corpCode = dartApiClient.getCorpCode("660");

            // Then
            assertThat(corpCode).isEqualTo("00164779");
        }

        @Test
        @DisplayName("stock_code 가 비어있는 비상장 회사는 매핑에 담지 않는다")
        void unlistedCompany_NotMapped() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess(corpCodeZip("CORPCODE.xml", SAMPLE_XML),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When
            String corpCode = dartApiClient.getCorpCode("999999");

            // Then
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("두 번째 조회는 캐시를 사용해 재다운로드하지 않는다")
        void secondCall_UsesCache() {
            // Given: 다운로드는 1회만 허용
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess(corpCodeZip("CORPCODE.xml", SAMPLE_XML),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When
            String first = dartApiClient.getCorpCode("005930");
            String second = dartApiClient.getCorpCode("000660");

            // Then
            mockServer.verify();
            assertThat(first).isEqualTo("00126380");
            assertThat(second).isEqualTo("00164779");
        }

        @Test
        @DisplayName("DART 비활성이면 다운로드 없이 null")
        void disabled_ReturnsNullWithoutDownload() {
            // Given
            ReflectionTestUtils.setField(dartApiClient, "dartApiKey", "");

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            mockServer.verify(); // 어떤 요청도 없어야 한다
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("stockCode 가 null 이면 다운로드 없이 null")
        void nullStockCode_ReturnsNull() {
            // When
            String corpCode = dartApiClient.getCorpCode(null);

            // Then
            mockServer.verify();
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("응답 본문이 비어 있으면 예외 없이 빈 매핑으로 degrade 한다")
        void emptyBody_DegradesToNull() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_OCTET_STREAM));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            mockServer.verify();
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("ZIP 안에 CORPCODE.xml 이 없으면 빈 매핑으로 degrade 한다")
        void zipWithoutCorpCodeXml_DegradesToNull() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess(corpCodeZip("README.txt", "not xml"),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            mockServer.verify();
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("ZIP 이 아닌 손상된 응답이어도 예외를 던지지 않는다")
        void corruptedZip_DoesNotThrow() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withSuccess("this-is-not-a-zip".getBytes(StandardCharsets.UTF_8),
                            MediaType.APPLICATION_OCTET_STREAM));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("다운로드가 HTTP 오류로 실패해도 예외를 던지지 않는다")
        void httpError_DoesNotThrow() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            mockServer.verify();
            assertThat(corpCode).isNull();
        }

        @Test
        @DisplayName("네트워크 오류로 실패해도 예외를 던지지 않는다")
        void networkError_DoesNotThrow() {
            // Given
            mockServer.expect(requestTo(CORP_CODE_URL))
                    .andRespond(withException(new IOException("connect timed out")));

            // When
            String corpCode = dartApiClient.getCorpCode("005930");

            // Then
            assertThat(corpCode).isNull();
        }
    }

    @Nested
    @DisplayName("getCompanyProfile - 회사개황(company.json)")
    class GetCompanyProfile {

        private static final String PROFILE_URL =
                BASE_URL + "/company.json?crtfc_key=" + API_KEY + "&corp_code=00126380";

        @Test
        @DisplayName("status=000 이면 응답 body 를 그대로 반환한다")
        void success_ReturnsBody() {
            // Given
            mockServer.expect(requestTo(PROFILE_URL))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","corp_name":"삼성전자","ceo_nm":"한종희"}
                            """, MediaType.APPLICATION_JSON));

            // When
            Map<String, Object> result = dartApiClient.getCompanyProfile("00126380");

            // Then
            mockServer.verify();
            assertThat(result).containsEntry("corp_name", "삼성전자");
        }

        @Test
        @DisplayName("status 가 000 이 아니면 null")
        void abnormalStatus_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(PROFILE_URL))
                    .andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다\"}",
                            MediaType.APPLICATION_JSON));

            // When / Then
            assertThat(dartApiClient.getCompanyProfile("00126380")).isNull();
        }

        @Test
        @DisplayName("응답 body 가 비어 있으면 null")
        void emptyBody_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(PROFILE_URL))
                    .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

            // When / Then
            assertThat(dartApiClient.getCompanyProfile("00126380")).isNull();
        }

        @Test
        @DisplayName("HTTP 오류가 나도 예외 없이 null")
        void httpError_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(PROFILE_URL))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            // When / Then
            assertThat(dartApiClient.getCompanyProfile("00126380")).isNull();
        }

        @Test
        @DisplayName("DART 비활성이거나 corp_code 가 비면 호출 없이 null")
        void disabledOrBlankCorpCode_ReturnsNull() {
            // Given / When / Then
            assertThat(dartApiClient.getCompanyProfile(null)).isNull();
            assertThat(dartApiClient.getCompanyProfile("  ")).isNull();

            ReflectionTestUtils.setField(dartApiClient, "dartApiKey", "");
            assertThat(dartApiClient.getCompanyProfile("00126380")).isNull();

            mockServer.verify(); // 어떤 요청도 없어야 한다
        }
    }

    @Nested
    @DisplayName("getDisclosureList - 공시검색(list.json)")
    class GetDisclosureList {

        private static final String LIST_URL = BASE_URL + "/list.json?crtfc_key=" + API_KEY
                + "&corp_code=00126380&bgn_de=20240101&end_de=20240131&page_count=10";

        @Test
        @DisplayName("status=000 이면 공시 목록 body 를 반환한다")
        void success_ReturnsBody() {
            // Given
            mockServer.expect(requestTo(LIST_URL))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[{"report_nm":"분기보고서"}]}
                            """, MediaType.APPLICATION_JSON));

            // When
            Map<String, Object> result = dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10);

            // Then
            mockServer.verify();
            assertThat(result).containsEntry("status", "000");
            assertThat(result).containsKey("list");
        }

        @Test
        @DisplayName("status=013(데이터 없음)은 null 이 아니라 body 를 그대로 반환한다")
        void noData_ReturnsBody() {
            // Given
            mockServer.expect(requestTo(LIST_URL))
                    .andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다\"}",
                            MediaType.APPLICATION_JSON));

            // When
            Map<String, Object> result = dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10);

            // Then
            assertThat(result).isNotNull();
            assertThat(result).containsEntry("status", "013");
        }

        @Test
        @DisplayName("그 외 오류 status 는 null")
        void errorStatus_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(LIST_URL))
                    .andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한 초과\"}",
                            MediaType.APPLICATION_JSON));

            // When / Then
            assertThat(dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10)).isNull();
        }

        @Test
        @DisplayName("응답 body 가 비어 있으면 null")
        void emptyBody_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(LIST_URL))
                    .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

            // When / Then
            assertThat(dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10)).isNull();
        }

        @Test
        @DisplayName("HTTP 오류가 나도 예외 없이 null")
        void httpError_ReturnsNull() {
            // Given
            mockServer.expect(requestTo(LIST_URL))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST));

            // When / Then
            assertThat(dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10)).isNull();
        }

        @Test
        @DisplayName("DART 비활성이거나 corp_code 가 비면 호출 없이 null")
        void disabledOrBlankCorpCode_ReturnsNull() {
            // Given / When / Then
            assertThat(dartApiClient.getDisclosureList(null, "20240101", "20240131", 10)).isNull();
            assertThat(dartApiClient.getDisclosureList("", "20240101", "20240131", 10)).isNull();

            ReflectionTestUtils.setField(dartApiClient, "dartApiKey", "");
            assertThat(dartApiClient.getDisclosureList("00126380", "20240101", "20240131", 10)).isNull();

            mockServer.verify(); // 어떤 요청도 없어야 한다
        }
    }
}
