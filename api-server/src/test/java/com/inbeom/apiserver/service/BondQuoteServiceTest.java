package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.client.KisBondClient;
import com.inbeom.apiserver.dto.bond.BondInfoResponse;
import com.inbeom.apiserver.dto.bond.BondIssueInfoResponse;
import com.inbeom.apiserver.dto.bond.BondOrderbookResponse;
import com.inbeom.apiserver.dto.bond.BondPriceResponse;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 채권 시세 조회 서비스 단위 테스트.
 *
 * <p>실제 {@link KisBondClient} 를 물려 두는 이유: TR_ID/경로 매핑이 이 서비스 → 클라이언트 →
 * {@link KisApiClient} 로 이어지는 경로에서 실제로 맞는지까지 함께 고정하기 위해서다.
 * 클라이언트를 목으로 바꾸면 그 연결이 검증되지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BondQuoteService 단위 테스트")
class BondQuoteServiceTest {

    @Mock
    @SuppressWarnings("rawtypes")
    private KisApiClient kisApiClient;

    @Mock
    private KisQuoteService kisQuoteService;

    private BondQuoteService bondQuoteService;

    private static final String BOND_CODE = "KR2033022D33";

    @BeforeEach
    void setUp() {
        bondQuoteService = new BondQuoteService(kisQuoteService, new KisBondClient(kisApiClient));
    }

    private void enableQuote() {
        given(kisQuoteService.isQuoteEnabled()).willReturn(true);
        given(kisQuoteService.getQuoteAccessToken()).willReturn("QUOTE_TOKEN");
        given(kisQuoteService.getQuoteBaseUrl()).willReturn("https://openapi.koreainvestment.com:9443");
        given(kisQuoteService.getQuoteAppKey()).willReturn("QUOTE_APP_KEY");
        given(kisQuoteService.getQuoteAppSecret()).willReturn("QUOTE_APP_SECRET");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenKisReturns(Map<String, Object> body) {
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willReturn(new ResponseEntity<Map>(body, HttpStatus.OK));
    }

    @Test
    @DisplayName("KIS 실패 시 예외 대신 notice 가 담긴 빈 결과를 준다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void kisFailure_returnsEmptyWithNotice() {
        enableQuote();
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willThrow(KisApiException.serverError("KIS down", null));

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getNotice()).isNotBlank();
        assertThat(result.getCurrentPrice()).isNull();
    }

    @Test
    @DisplayName("우리 쪽 rate limit 에 걸린 경우는 'KIS 점검'이 아닌 별도 안내를 준다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rateLimited_hasDistinctNotice() {
        enableQuote();
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willThrow(new KisRateLimitExceededException("limit"));

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_BUSY);
    }

    @Test
    @DisplayName("quote 자격증명이 없으면 KIS 를 부르지 않고 안내만 준다")
    void quoteDisabled_returnsNoticeWithoutCallingKis() {
        given(kisQuoteService.isQuoteEnabled()).willReturn(false);

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
        assertThat(result.getCurrentPrice()).isNull();
    }

    @Test
    @DisplayName("호가가 비어도(유동성 부족) 예외를 던지지 않는다")
    void emptyOrderbook_doesNotThrow() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0", "output", Map.of()));

        assertThatCode(() -> bondQuoteService.getOrderbook(BOND_CODE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("현재가를 BigDecimal 로 파싱해 소수점을 잃지 않는다")
    void unitPriceKeepsDecimals() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0", "output", Map.of("bond_prpr", "9850.5")));

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("9850.5"));
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("신용등급은 평가사별 4개를 모두 보존한다")
    void creditGradesFromAllAgencies() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0", "output", Map.of(
                "kis_crdt_grad_text", "AA+", "kbp_crdt_grad_text", "AA",
                "nice_crdt_grad_text", "AA+", "fnp_crdt_grad_text", "")));

        BondIssueInfoResponse result = bondQuoteService.getIssueInfo(BOND_CODE);

        assertThat(result.getKisCreditGrade()).isEqualTo("AA+");
        assertThat(result.getKbpCreditGrade()).isEqualTo("AA");
        assertThat(result.getNiceCreditGrade()).isEqualTo("AA+");
        assertThat(result.getFnpCreditGrade()).isNull();
    }

    @Test
    @DisplayName("호가는 bond_askpN(접두사 있음)과 askp_rsqnN(접두사 없음)을 각각 읽는다")
    void orderbookFieldPrefixesDiffer() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0", "output", Map.of(
                "aspr_acpt_hour", "153000",
                "bond_askp1", "10050.5", "askp_rsqn1", "1000", "seln_ernn_rate1", "3.21",
                "bond_bidp1", "10000.1", "bidp_rsqn1", "2000", "shnu_ernn_rate1", "3.35",
                "total_askp_rsqn", "5000", "total_bidp_rsqn", "7000")));

        BondOrderbookResponse result = bondQuoteService.getOrderbook(BOND_CODE);

        assertThat(result.getAsks()).isNotEmpty();
        assertThat(result.getAsks().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("10050.5"));
        assertThat(result.getAsks().get(0).getRemainQty()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(result.getBids().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("10000.1"));
        assertThat(result.getBids().get(0).getRemainQty()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(result.getTotalAskQty()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("기본조회는 종목명과 통화코드를 보존한다 (외화표시채권 판별용)")
    void bondInfoKeepsNameAndCurrency() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0", "output", Map.of(
                "pdno", BOND_CODE,
                "ksd_bond_item_name", "국고채권01500-3906(19-4)",
                "iso_crcy_cd", "KRW",
                "ksd_rcvg_bond_srfc_inrt", "1.5",
                "sprx_psbl_yn", "Y")));

        BondInfoResponse result = bondQuoteService.getBondInfo(BOND_CODE);

        assertThat(result.getBondCode()).isEqualTo(BOND_CODE);
        assertThat(result.getBondName()).isEqualTo("국고채권01500-3906(19-4)");
        assertThat(result.getCurrencyCode()).isEqualTo("KRW");
        assertThat(result.getCouponRate()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(result.getSeparateTaxationPossible()).isEqualTo("Y");
    }

    @Test
    @DisplayName("rt_cd != 0 이면 빈 결과 + notice (예외 전파 금지)")
    void rtCodeNotZero_degradesGracefully() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "1", "msg1", "조회할 자료가 없습니다"));

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("output 이 배열로 와도(단건/다건 표기 흔들림) 첫 행을 읽는다")
    void outputAsListIsAlsoParsed() {
        enableQuote();
        givenKisReturns(Map.of("rt_cd", "0",
                "output", java.util.List.of(Map.of("bond_prpr", "10000.25"))));

        BondPriceResponse result = bondQuoteService.getPrice(BOND_CODE);

        assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("10000.25"));
    }
}
