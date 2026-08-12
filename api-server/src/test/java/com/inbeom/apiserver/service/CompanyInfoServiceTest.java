package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.DartApiClient;
import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.dto.company.BasicInfoResponse;
import com.inbeom.apiserver.dto.company.DisclosuresResponse;
import com.inbeom.apiserver.dto.company.FinancialsResponse;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyInfoService 단위 테스트")
class CompanyInfoServiceTest {

    @Mock
    private KisQuoteService kisQuoteService;

    @Mock
    private KisQuoteClient kisQuoteClient;

    @Mock
    private KisApiClient kisApiClient;

    @Mock
    private DartApiClient dartApiClient;

    @InjectMocks
    private CompanyInfoService companyInfoService;

    private static final String STOCK_CODE = "005930";
    private static final String CORP_CODE = "00126380";
    private static final String INCOME_ENDPOINT = "/uapi/domestic-stock/v1/finance/income-statement";
    private static final String RATIO_ENDPOINT = "/uapi/domestic-stock/v1/finance/financial-ratio";
    private static final String STABILITY_ENDPOINT = "/uapi/domestic-stock/v1/finance/stability-ratio";

    private static final String NOTICE_DART =
            "공시 데이터가 연동되지 않았습니다 (DART_API_KEY 필요)";
    private static final String NOTICE_DART_PROFILE =
            "회사 개황(공시)이 연동되지 않았습니다 (DART_API_KEY 필요)";

    private Map<String, Object> priceOutput;
    private Map<String, Object> dartProfile;

    @BeforeEach
    void setUp() {
        priceOutput = new HashMap<>();
        priceOutput.put("stck_prpr", "71500");
        priceOutput.put("prdy_ctrt", "-1.65");
        priceOutput.put("per", "12.34");
        priceOutput.put("pbr", "1.05");
        priceOutput.put("eps", "5800");
        priceOutput.put("bps", "68000");
        priceOutput.put("lstn_stcn", "5969782550");
        priceOutput.put("w52_hgpr", "88800");
        priceOutput.put("w52_lwpr", "49900");
        priceOutput.put("hts_avls", "4268394");  // 억원
        priceOutput.put("bstp_kor_isnm", "전기전자");

        dartProfile = new HashMap<>();
        dartProfile.put("corp_name", "삼성전자");
        dartProfile.put("corp_name_eng", "SAMSUNG ELECTRONICS CO,.LTD");
        dartProfile.put("adres", "경기도 수원시 영통구");
        dartProfile.put("hm_url", "www.samsung.com/sec");
        dartProfile.put("ceo_nm", "한종희");
        dartProfile.put("est_dt", "19690113");
        dartProfile.put("induty_code", "264");
    }

    /** quote(실전 시세) 자격증명이 정상 설정된 상태로 스텁. */
    private void enableQuote() {
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn("QUOTE_TOKEN");
        when(kisQuoteService.getQuoteBaseUrl()).thenReturn("https://openapi.koreainvestment.com:9443");
        when(kisQuoteService.getQuoteAppKey()).thenReturn("QUOTE_APP_KEY");
        when(kisQuoteService.getQuoteAppSecret()).thenReturn("QUOTE_APP_SECRET");
    }

    /**
     * 현재가 시세 스텁. 서비스는 stale(캐시 폴백) 여부까지 알아야 해서
     * {@code fetchCurrentPriceResult} 를 쓰므로, 기본은 "신선한 값"으로 스텁한다.
     */
    private void stubPrice(Map<String, Object> output) {
        when(kisQuoteClient.fetchCurrentPriceResult(STOCK_CODE))
                .thenReturn(new KisQuoteClient.QuoteResult(output, false, false));
    }

    private ResponseEntity<Map> kisOk(List<Map<String, Object>> output) {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", output);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    private ResponseEntity<Map> kisError() {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "조회할 자료가 없습니다");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // getBasicInfo
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getBasicInfo - KIS 시세 + DART 회사개황 조합 성공")
    void getBasicInfo_Success() {
        // Given
        stubPrice(priceOutput);
        when(dartApiClient.isEnabled()).thenReturn(true);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getCompanyProfile(CORP_CODE)).thenReturn(dartProfile);

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(result.getCurrentPrice()).isEqualTo(71500L);
        assertThat(result.getChangeRate()).isEqualByComparingTo(new BigDecimal("-1.65"));
        assertThat(result.getPer()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(result.getPbr()).isEqualByComparingTo(new BigDecimal("1.05"));
        assertThat(result.getEps()).isEqualByComparingTo(new BigDecimal("5800"));
        assertThat(result.getBps()).isEqualByComparingTo(new BigDecimal("68000"));
        assertThat(result.getListedShares()).isEqualTo(5969782550L);
        assertThat(result.getWeek52High()).isEqualTo(88800L);
        assertThat(result.getWeek52Low()).isEqualTo(49900L);
        // hts_avls(억원) × 100,000,000 → 원
        assertThat(result.getMarketCap()).isEqualTo(4268394L * 100_000_000L);
        // 업종명은 KIS bstp_kor_isnm 우선
        assertThat(result.getSector()).isEqualTo("전기전자");
        // 종목명은 DART corp_name
        assertThat(result.getStockName()).isEqualTo("삼성전자");
        assertThat(result.getStockNameEn()).isEqualTo("SAMSUNG ELECTRONICS CO,.LTD");
        assertThat(result.getAddress()).isEqualTo("경기도 수원시 영통구");
        assertThat(result.getHomepage()).isEqualTo("www.samsung.com/sec");
        assertThat(result.getCeoName()).isEqualTo("한종희");
        assertThat(result.getEstablishedDate()).isEqualTo("19690113");
        assertThat(result.getHasDartProfile()).isTrue();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getBasicInfo - KIS/DART 모두 미연동 시 두 notice 를 ' / ' 로 결합")
    void getBasicInfo_BothUnavailable_JoinsNotices() {
        // Given
        stubPrice(null);
        when(kisQuoteClient.unavailableNotice(false)).thenReturn(KisQuoteClient.NOTICE_KIS_QUOTE);
        when(dartApiClient.isEnabled()).thenReturn(false);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(null);

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getStockName()).isNull();
        assertThat(result.getSector()).isNull();
        assertThat(result.getHasDartProfile()).isFalse();
        assertThat(result.getNotice())
                .isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE + " / " + NOTICE_DART_PROFILE);
    }

    @Test
    @DisplayName("getBasicInfo - DART 조회 예외 시 예외 전파 없이 KIS 데이터만 반환")
    void getBasicInfo_DartThrows_DegradesGracefully() {
        // Given
        stubPrice(priceOutput);
        when(dartApiClient.isEnabled()).thenReturn(true);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenThrow(new RuntimeException("DART timeout"));

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getCurrentPrice()).isEqualTo(71500L);
        assertThat(result.getStockName()).isNull();
        assertThat(result.getHasDartProfile()).isFalse();
        assertThat(result.getSector()).isEqualTo("전기전자");
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getBasicInfo - KIS 업종명이 없으면 DART induty_code 로 sector 폴백")
    void getBasicInfo_SectorFallsBackToDartInduty() {
        // Given
        priceOutput.remove("bstp_kor_isnm");
        stubPrice(priceOutput);
        when(dartApiClient.isEnabled()).thenReturn(true);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getCompanyProfile(CORP_CODE)).thenReturn(dartProfile);

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getSector()).isEqualTo("264");
    }

    @Test
    @DisplayName("getBasicInfo - corp_code 는 있으나 회사개황이 null 이면 hasDartProfile=false")
    void getBasicInfo_NullDartProfile_HasDartProfileFalse() {
        // Given
        stubPrice(priceOutput);
        when(dartApiClient.isEnabled()).thenReturn(true);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getCompanyProfile(CORP_CODE)).thenReturn(null);

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getHasDartProfile()).isFalse();
        assertThat(result.getStockName()).isNull();
        assertThat(result.getNotice()).isNull();
    }

    // ---------------------------------------------------------------------
    // getFinancials
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getFinancials - 손익/재무비율/안정성비율 + 시세 PER·PBR 조합 성공")
    void getFinancials_Success() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), eq(INCOME_ENDPOINT), eq("FHKST66430200"),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of(
                        Map.of("stac_yymm", "202412", "sale_account", "3008709",
                                "bsop_prti", "326649", "thtr_ntin", "344513"),
                        Map.of("stac_yymm", "202312", "sale_account", "2589355",
                                "bsop_prti", "65670", "thtr_ntin", "154871"))));
        when(kisApiClient.get(anyString(), eq(RATIO_ENDPOINT), eq("FHKST66430300"),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of(Map.of("roe_val", "9.51", "eps", "5060"))));
        when(kisApiClient.get(anyString(), eq(STABILITY_ENDPOINT), eq("FHKST66430600"),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of(Map.of("lblt_rate", "25.36", "crnt_rate", "260.14"))));
        stubPrice(priceOutput);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(result.getAnnual()).hasSize(2);
        assertThat(result.getAnnual().get(0).getYear()).isEqualTo("2024");
        assertThat(result.getAnnual().get(0).getRevenue()).isEqualTo(3008709L);
        assertThat(result.getAnnual().get(0).getOperatingProfit()).isEqualTo(326649L);
        assertThat(result.getAnnual().get(0).getNetProfit()).isEqualTo(344513L);
        // 재무비율 EPS 는 최신 연도(annual[0])에만 매핑
        assertThat(result.getAnnual().get(0).getEps()).isEqualByComparingTo(new BigDecimal("5060"));
        assertThat(result.getAnnual().get(1).getYear()).isEqualTo("2023");
        assertThat(result.getAnnual().get(1).getEps()).isNull();

        assertThat(result.getRatios().getRoe()).isEqualByComparingTo(new BigDecimal("9.51"));
        assertThat(result.getRatios().getDebtRatio()).isEqualByComparingTo(new BigDecimal("25.36"));
        assertThat(result.getRatios().getCurrentRatio()).isEqualByComparingTo(new BigDecimal("260.14"));
        assertThat(result.getRatios().getPer()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(result.getRatios().getPbr()).isEqualByComparingTo(new BigDecimal("1.05"));
        // ROA 는 KIS 미제공 → 항상 null
        assertThat(result.getRatios().getRoa()).isNull();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getFinancials - 연간 손익은 최대 4개년까지만 매핑")
    void getFinancials_LimitsAnnualToFourYears() {
        // Given
        enableQuote();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int year = 2024; year >= 2019; year--) {
            rows.add(Map.of("stac_yymm", year + "12", "sale_account", "1000",
                    "bsop_prti", "100", "thtr_ntin", "50"));
        }
        when(kisApiClient.get(anyString(), eq(INCOME_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(rows));
        when(kisApiClient.get(anyString(), eq(RATIO_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of()));
        when(kisApiClient.get(anyString(), eq(STABILITY_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of()));
        stubPrice(null);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).hasSize(4);
        assertThat(result.getAnnual().get(0).getYear()).isEqualTo("2024");
        assertThat(result.getAnnual().get(3).getYear()).isEqualTo("2021");
        // 연간 손익 데이터가 있으므로 notice 없음
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getFinancials - quote 비활성 시 KIS 호출 없이 빈 응답 + notice")
    void getFinancials_QuoteDisabled_ReturnsEmptyWithNotice() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(false);
        stubPrice(null);
        when(kisQuoteClient.unavailableNotice(false)).thenReturn(KisQuoteClient.NOTICE_KIS_QUOTE);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).isEmpty();
        assertThat(result.getRatios().getRoe()).isNull();
        assertThat(result.getRatios().getPer()).isNull();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getFinancials - 자체 rate limit 으로 degrade 되면 'KIS 점검'이 아니라 한도 문구로 안내한다")
    void getFinancials_RateLimited_UsesBusyNotice() {
        // Given: 재무 4개 호출이 전부 우리 쪽 토큰 버킷에 걸린다. KIS 는 멀쩡하므로
        // "KIS 점검 또는 일시적 연동 오류" 라고 안내하면 원인을 잘못 알리는 셈이다.
        enableQuote();
        when(kisApiClient.get(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException("KIS 호출 한도를 초과해 요청을 보내지 않았습니다"));
        when(kisQuoteClient.fetchCurrentPriceResult(STOCK_CODE))
                .thenReturn(new KisQuoteClient.QuoteResult(null, false, true));
        when(kisQuoteClient.unavailableNotice(true)).thenReturn(KisQuoteClient.NOTICE_KIS_BUSY);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_BUSY);
    }

    @Test
    @DisplayName("getBasicInfo - 시세가 자체 rate limit 으로 비면 한도 문구로 안내한다")
    void getBasicInfo_RateLimited_UsesBusyNotice() {
        // Given
        when(kisQuoteClient.fetchCurrentPriceResult(STOCK_CODE))
                .thenReturn(new KisQuoteClient.QuoteResult(null, false, true));
        when(kisQuoteClient.unavailableNotice(true)).thenReturn(KisQuoteClient.NOTICE_KIS_BUSY);
        when(dartApiClient.isEnabled()).thenReturn(true);
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(null);

        // When
        BasicInfoResponse result = companyInfoService.getBasicInfo(STOCK_CODE);

        // Then
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_BUSY);
    }

    @Test
    @DisplayName("getFinancials - 토큰 획득 실패 시 재무 호출 없이 degrade")
    void getFinancials_TokenUnavailable_Degrades() {
        // Given
        when(kisQuoteService.isQuoteEnabled()).thenReturn(true);
        when(kisQuoteService.getQuoteAccessToken()).thenReturn(null);
        stubPrice(null);
        when(kisQuoteClient.unavailableNotice(false)).thenReturn(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getFinancials - KIS rt_cd != 0 이면 해당 섹션은 빈 값 + notice")
    void getFinancials_KisReturnsError_DegradesWithNotice() {
        // Given
        enableQuote();
        when(kisApiClient.get(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisError());
        stubPrice(null);
        when(kisQuoteClient.unavailableNotice(false)).thenReturn(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).isEmpty();
        assertThat(result.getRatios().getRoe()).isNull();
        assertThat(result.getRatios().getDebtRatio()).isNull();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("getFinancials - KIS 호출 예외는 섹션별로 격리되어 나머지는 정상 매핑")
    void getFinancials_ExceptionIsIsolatedPerSection() {
        // Given - 손익계산서만 예외, 나머지는 정상
        enableQuote();
        when(kisApiClient.get(anyString(), eq(INCOME_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenThrow(new RuntimeException("KIS timeout"));
        when(kisApiClient.get(anyString(), eq(RATIO_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of(Map.of("roe_val", "9.51", "eps", "5060"))));
        when(kisApiClient.get(anyString(), eq(STABILITY_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of(Map.of("lblt_rate", "25.36", "crnt_rate", "260.14"))));
        stubPrice(null);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).isEmpty();
        assertThat(result.getRatios().getRoe()).isEqualByComparingTo(new BigDecimal("9.51"));
        assertThat(result.getRatios().getDebtRatio()).isEqualByComparingTo(new BigDecimal("25.36"));
        // 비율 데이터가 있으므로 notice 없음
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getFinancials - output 대신 output1 로 오는 응답도 추출")
    void getFinancials_ExtractsOutput1Fallback() {
        // Given
        enableQuote();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output1", List.of(Map.of("stac_yymm", "202412", "sale_account", "3008709",
                "bsop_prti", "326649", "thtr_ntin", "344513")));
        when(kisApiClient.get(anyString(), eq(INCOME_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
        when(kisApiClient.get(anyString(), eq(RATIO_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of()));
        when(kisApiClient.get(anyString(), eq(STABILITY_ENDPOINT), anyString(),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(kisOk(List.of()));
        stubPrice(null);

        // When
        FinancialsResponse result = companyInfoService.getFinancials(STOCK_CODE);

        // Then
        assertThat(result.getAnnual()).hasSize(1);
        assertThat(result.getAnnual().get(0).getYear()).isEqualTo("2024");
    }

    // ---------------------------------------------------------------------
    // getDisclosures
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getDisclosures - DART 공시 목록 매핑 (type 분류 / 날짜 변환 / 중요도)")
    void getDisclosures_Success() {
        // Given
        Map<String, Object> body = new HashMap<>();
        body.put("status", "000");
        body.put("list", List.of(
                Map.of("rcept_no", "20260731000123", "report_nm", "분기보고서 (2026.06)",
                        "rcept_dt", "20260731"),
                Map.of("rcept_no", "20260715000456", "report_nm", "영업(잠정)실적(공정공시)",
                        "rcept_dt", "20260715"),
                Map.of("rcept_no", "20260610000789", "report_nm", "주요사항보고서(유상증자결정)",
                        "rcept_dt", "20260610"),
                Map.of("rcept_no", "20260601000111", "report_nm", "기타경영사항",
                        "rcept_dt", "20260601")));

        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getDisclosureList(eq(CORP_CODE), anyString(), anyString(), eq(20)))
                .thenReturn(body);
        when(dartApiClient.isEnabled()).thenReturn(true);

        // When
        DisclosuresResponse result = companyInfoService.getDisclosures(STOCK_CODE);

        // Then
        assertThat(result.getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(result.getDisclosures()).hasSize(4);
        assertThat(result.getNotice()).isNull();

        DisclosuresResponse.Disclosure quarterly = result.getDisclosures().get(0);
        assertThat(quarterly.getId()).isEqualTo("20260731000123");
        assertThat(quarterly.getType()).isEqualTo("정기공시");
        assertThat(quarterly.getDate()).isEqualTo("2026-07-31");
        assertThat(quarterly.getImportant()).isFalse();

        DisclosuresResponse.Disclosure earnings = result.getDisclosures().get(1);
        assertThat(earnings.getType()).isEqualTo("공정공시");
        assertThat(earnings.getImportant()).isTrue();

        DisclosuresResponse.Disclosure material = result.getDisclosures().get(2);
        assertThat(material.getType()).isEqualTo("주요사항");
        assertThat(material.getImportant()).isTrue();

        DisclosuresResponse.Disclosure other = result.getDisclosures().get(3);
        assertThat(other.getType()).isEqualTo("기타");
        assertThat(other.getImportant()).isFalse();
    }

    @Test
    @DisplayName("getDisclosures - DART 비활성 시 빈 리스트 + notice")
    void getDisclosures_DartDisabled_ReturnsEmptyWithNotice() {
        // Given
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(null);
        when(dartApiClient.isEnabled()).thenReturn(false);

        // When
        DisclosuresResponse result = companyInfoService.getDisclosures(STOCK_CODE);

        // Then
        assertThat(result.getDisclosures()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(NOTICE_DART);
        verify(dartApiClient, never()).getDisclosureList(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("getDisclosures - DART 호출 예외 시 예외 전파 없이 빈 리스트")
    void getDisclosures_DartThrows_ReturnsEmpty() {
        // Given
        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getDisclosureList(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("DART timeout"));
        when(dartApiClient.isEnabled()).thenReturn(true);

        // When
        DisclosuresResponse result = companyInfoService.getDisclosures(STOCK_CODE);

        // Then
        assertThat(result.getDisclosures()).isEmpty();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getDisclosures - 데이터 없음(list 부재)이면 빈 리스트, 비-Map 항목은 스킵")
    void getDisclosures_SkipsNonMapItemsAndMissingList() {
        // Given
        Map<String, Object> body = new HashMap<>();
        body.put("status", "000");
        body.put("list", List.of("문자열-항목",
                Map.of("rcept_no", "1", "report_nm", "사업보고서", "rcept_dt", "20260331")));

        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getDisclosureList(eq(CORP_CODE), anyString(), anyString(), eq(20)))
                .thenReturn(body);
        when(dartApiClient.isEnabled()).thenReturn(true);

        // When
        DisclosuresResponse result = companyInfoService.getDisclosures(STOCK_CODE);

        // Then
        assertThat(result.getDisclosures()).hasSize(1);
        assertThat(result.getDisclosures().get(0).getType()).isEqualTo("정기공시");
        assertThat(result.getDisclosures().get(0).getDate()).isEqualTo("2026-03-31");
    }

    @Test
    @DisplayName("getDisclosures - rcept_dt 형식이 8자리가 아니면 원본 유지")
    void getDisclosures_MalformedDate_KeepsRawValue() {
        // Given
        Map<String, Object> body = new HashMap<>();
        body.put("status", "000");
        body.put("list", List.of(Map.of("rcept_no", "1", "report_nm", "사업보고서", "rcept_dt", "2026")));

        when(dartApiClient.getCorpCode(STOCK_CODE)).thenReturn(CORP_CODE);
        when(dartApiClient.getDisclosureList(eq(CORP_CODE), anyString(), anyString(), eq(20)))
                .thenReturn(body);
        when(dartApiClient.isEnabled()).thenReturn(true);

        // When
        DisclosuresResponse result = companyInfoService.getDisclosures(STOCK_CODE);

        // Then
        assertThat(result.getDisclosures().get(0).getDate()).isEqualTo("2026");
    }
}
