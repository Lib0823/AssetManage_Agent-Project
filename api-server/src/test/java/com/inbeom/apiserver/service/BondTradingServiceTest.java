package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.client.KisBondClient;
import com.inbeom.apiserver.config.KisBondProperties;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.bond.BondBalanceResponse;
import com.inbeom.apiserver.dto.bond.BondHoldingResponse;
import com.inbeom.apiserver.dto.bond.BondSellRequest;
import com.inbeom.apiserver.dto.bond.BondTradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 채권 잔고 조회 · 매도 주문 · 거래내역 단위 테스트.
 *
 * <p>실제 {@link KisBondClient} 를 물려 둔다 — TR_ID/경로/파라미터 이름이 서비스에서 KIS 요청까지
 * 실제로 어떻게 나가는지가 이 기능의 핵심 위험(로트 식별자 이름 불일치)이기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BondTradingService 단위 테스트")
class BondTradingServiceTest {

    @Mock
    @SuppressWarnings("rawtypes")
    private KisApiClient kisApiClient;

    @Mock
    private KisAuthService kisAuthService;

    @Mock
    private com.inbeom.apiserver.repository.UserRepository userRepository;

    private BondTradingService bondTradingService;

    private static final Long USER_ID = 1L;
    private static final Long KIS_ACCOUNT_ID = 10L;
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final String BOND_CODE = "KR2033022D33";

    @BeforeEach
    void setUp() {
        KisBondProperties bondProperties = new KisBondProperties();
        bondTradingService = new BondTradingService(
                kisAuthService, new KisBondClient(kisApiClient), userRepository, bondProperties);
    }

    /** KIS 계좌가 등록된 정상 사용자 + 자격증명/토큰 스텁. */
    private void givenLinkedUser() {
        User user = User.builder().id(USER_ID).build();
        user.setKisAccount(UserKisAccount.builder().id(KIS_ACCOUNT_ID).build());
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        lenient().when(kisAuthService.getKisAccessToken(KIS_ACCOUNT_ID)).thenReturn("KIS_TOKEN");
        lenient().when(kisAuthService.getKisCredentials(KIS_ACCOUNT_ID)).thenReturn(
                new KisCredentials("APP_KEY", "APP_SECRET", "12345678", "01", BASE_URL));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenGetReturns(Map<String, Object> body) {
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willReturn(new ResponseEntity<Map>(body, HttpStatus.OK));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenPostReturns(Map<String, Object> body) {
        given(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willReturn(new ResponseEntity<Map>(body, HttpStatus.OK));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> captureSellBody() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(kisApiClient).post(anyString(), eq(KisBondClient.PATH_SELL), eq(KisBondClient.TR_SELL),
                anyString(), anyString(), anyString(), body.capture(), eq(Map.class));
        return (Map<String, Object>) body.getValue();
    }

    private BondSellRequest sellRequest(BigDecimal unitPrice, Boolean separateTaxation) {
        return new BondSellRequest(BOND_CODE, "국고채권", new BigDecimal("10"), unitPrice,
                "20260315", "3", separateTaxation);
    }

    private static Map<String, Object> okOrderBody() {
        return Map.of("rt_cd", "0", "msg1", "정상처리",
                "output", Map.of("ODNO", "0000123456", "ORD_TMD", "093000",
                        "KRX_FWDG_ORD_ORGNO", "12345"));
    }

    // ── Task 2: 잔고 ────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS 계좌가 없으면 KisAccountNotFoundException (NPE 아님)")
    void noKisAccount_throwsKisAccountNotFound() {
        User user = User.builder().id(USER_ID).build();   // kisAccount == null
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> bondTradingService.getBalance(USER_ID))
                .isInstanceOf(KisAccountNotFoundException.class);
    }

    @Test
    @DisplayName("tr_cont=M 이면 다음 페이지를 이어서 조회한다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void continuationHeader_fetchesNextPage() {
        givenLinkedUser();
        HttpHeaders more = new HttpHeaders();
        more.set("tr_cont", "M");
        Map<String, Object> page1 = Map.of("rt_cd", "0",
                "ctx_area_fk200", "FK", "ctx_area_nk200", "NK",
                "output", List.of(Map.of("pdno", "KR1111111111", "buy_dt", "20260101", "buy_sqno", "1")));
        Map<String, Object> page2 = Map.of("rt_cd", "0",
                "output", List.of(Map.of("pdno", "KR2222222222", "buy_dt", "20260201", "buy_sqno", "2")));

        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willReturn(new ResponseEntity<Map>(page1, more, HttpStatus.OK))
                .willReturn(new ResponseEntity<Map>(page2, HttpStatus.OK));

        BondBalanceResponse result = bondTradingService.getBalance(USER_ID);

        assertThat(result.getHoldings()).hasSize(2);   // 두 페이지가 합쳐진다
    }

    @Test
    @DisplayName("tr_cont 가 계속 M 이어도 페이지 상한에서 멈춘다 (무한 루프 방지)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void continuationHasPageCap() {
        givenLinkedUser();
        HttpHeaders more = new HttpHeaders();
        more.set("tr_cont", "M");
        Map<String, Object> page = Map.of("rt_cd", "0",
                "ctx_area_fk200", "FK", "ctx_area_nk200", "NK",
                "output", List.of(Map.of("pdno", BOND_CODE, "buy_dt", "20260101", "buy_sqno", "1")));
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willReturn(new ResponseEntity<Map>(page, more, HttpStatus.OK));

        BondBalanceResponse result = bondTradingService.getBalance(USER_ID);

        assertThat(result.getHoldings())
                .as("상한이 없으면 KIS 가 계속 M 을 주는 순간 서버가 영원히 돈다")
                .hasSize(BondTradingService.MAX_BALANCE_PAGES);
    }

    @Test
    @DisplayName("매도에 필요한 로트 정보(buy_dt, buy_sqno)를 보존한다")
    void holdingKeepsLotIdentifiers() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of(
                Map.of("pdno", BOND_CODE, "buy_dt", "20260315", "buy_sqno", "3"))));

        BondHoldingResponse holding = bondTradingService.getBalance(USER_ID).getHoldings().get(0);

        assertThat(holding.getBuyDate()).isEqualTo("20260315");
        assertThat(holding.getBuySeq()).isEqualTo("3");   // 응답은 buy_sqno, 요청은 BUY_SEQ
    }

    @Test
    @DisplayName("잔고 조회는 KIS 필수 파라미터 7개를 모두 보낸다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void balanceSendsAllRequiredParams() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of()));

        bondTradingService.getBalance(USER_ID);

        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(eq(BASE_URL), eq(KisBondClient.PATH_INQUIRE_BALANCE),
                eq(KisBondClient.TR_INQUIRE_BALANCE), anyString(), anyString(), anyString(),
                params.capture(), eq(Map.class));

        assertThat(params.getValue())
                .containsKeys("CANO", "ACNT_PRDT_CD", "INQR_CNDT", "PDNO", "BUY_DT",
                        "CTX_AREA_FK200", "CTX_AREA_NK200")
                .containsEntry("INQR_CNDT", "00")
                .containsEntry("PDNO", "")      // null 이 아니라 빈 문자열이어야 한다
                .containsEntry("BUY_DT", "");
    }

    @Test
    @DisplayName("원화(KRW)가 아닌 채권은 합산에서 제외한다")
    void nonKrwBondsExcludedFromTotal() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of(
                Map.of("pdno", "KR1111111111", "buy_dt", "20260101", "buy_sqno", "1",
                        "buy_amt", "1000000", "iso_crcy_cd", "KRW"),
                Map.of("pdno", "US2222222222", "buy_dt", "20260101", "buy_sqno", "2",
                        "buy_amt", "5000", "iso_crcy_cd", "USD"))));

        BondBalanceResponse result = bondTradingService.getBalance(USER_ID);

        assertThat(result.getHoldings()).hasSize(2);   // 목록에는 보인다
        assertThat(result.getTotalBuyAmount())
                .as("USD 5000 을 원화 합계에 더하면 총자산이 조용히 틀어진다")
                .isEqualByComparingTo(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("분리과세 여부를 sprx_qty/agrx_qty 에서 유도한다")
    void separateTaxationDerivedFromQuantities() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of(
                Map.of("pdno", BOND_CODE, "buy_dt", "20260101", "buy_sqno", "1",
                        "sprx_qty", "10", "agrx_qty", "0"))));

        BondHoldingResponse holding = bondTradingService.getBalance(USER_ID).getHoldings().get(0);

        assertThat(holding.getSeparateTaxation()).isTrue();
    }

    @Test
    @DisplayName("KIS 호출이 실패하면 빈 목록 + notice 로 degrade 한다 (조회 경로)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void balanceKisFailure_degradesGracefully() {
        givenLinkedUser();
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willThrow(new ResourceAccessException("timeout"));

        BondBalanceResponse result = bondTradingService.getBalance(USER_ID);

        assertThat(result.getHoldings()).isEmpty();
        assertThat(result.getNotice()).isNotBlank();
    }

    @Test
    @DisplayName("잔고 응답에 서버 환산 계수를 실어 보낸다 (프론트가 같은 값을 쓰도록)")
    void balanceCarriesFaceValueDivisor() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of()));

        BondBalanceResponse result = bondTradingService.getBalance(USER_ID);

        assertThat(result.getFaceValueDivisor()).isEqualByComparingTo(new BigDecimal("100"));
    }

    // ── Task 3: 매도 ────────────────────────────────────────────────────

    @Test
    @DisplayName("매도 주문이 로트 식별자를 포함한 KIS 파라미터로 나간다")
    void sell_sendsLotIdentifiersAndRequiredFields() {
        givenLinkedUser();
        givenPostReturns(okOrderBody());

        bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("9850.5"), Boolean.TRUE));

        Map<String, Object> sent = captureSellBody();
        assertThat(sent.get("PDNO")).isEqualTo(BOND_CODE);
        assertThat(sent.get("ORD_QTY2")).isEqualTo("10");
        assertThat(sent.get("BOND_ORD_UNPR")).isEqualTo("9850.5");   // 소수점·비지수 표기 보존
        assertThat(sent.get("BUY_DT")).isEqualTo("20260315");
        assertThat(sent.get("BUY_SEQ")).isEqualTo("3");              // 요청은 BUY_SEQ (응답은 buy_sqno)
        assertThat(sent).containsKeys("ORD_DVSN", "SPRX_YN", "SLL_AGCO_OPPS_SLL_YN",
                "SAMT_MKET_PTCI_YN", "BOND_RTL_MKET_YN", "CANO", "ACNT_PRDT_CD");
    }

    @Test
    @DisplayName("분리과세여부는 요청값을 반영한다 — 임의 고정이 아니다")
    void separateTaxationFlagIsNotHardcoded() {
        givenLinkedUser();
        givenPostReturns(okOrderBody());

        bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("10000"), Boolean.TRUE));
        assertThat(captureSellBody().get("SPRX_YN")).isEqualTo("Y");

        clearInvocations(kisApiClient);

        bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("10000"), Boolean.FALSE));
        assertThat(captureSellBody().get("SPRX_YN")).isEqualTo("N");
    }

    @Test
    @DisplayName("분리과세여부가 없으면 임의 기본값을 쓰지 않고 명확히 거부한다")
    void missingSeparateTaxation_isRejectedNotDefaulted() {
        assertThatThrownBy(() ->
                bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("10000"), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("분리과세");
    }

    @Test
    @DisplayName("로트 식별자가 비면 주문을 보내지 않는다")
    void missingLotIdentifiers_isRejectedBeforeCallingKis() {
        BondSellRequest noLot = new BondSellRequest(BOND_CODE, "국고채권",
                new BigDecimal("10"), new BigDecimal("10000"), "", "3", Boolean.TRUE);

        assertThatThrownBy(() -> bondTradingService.sell(USER_ID, noLot))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("매수일자");
    }

    @Test
    @DisplayName("주문 실패는 degrade 하지 않고 예외를 전파한다")
    void orderFailure_propagates() {
        givenLinkedUser();
        givenPostReturns(Map.of("rt_cd", "1", "msg1", "보유수량 부족"));

        assertThatThrownBy(() ->
                bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("10000"), Boolean.TRUE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("보유수량 부족");
    }

    @Test
    @DisplayName("소수 단가가 지수표기로 변질되지 않는다")
    void smallUnitPriceNotSerializedAsScientificNotation() {
        givenLinkedUser();
        givenPostReturns(okOrderBody());

        bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("0.0001"), Boolean.TRUE));

        // BigDecimal.toString() 은 0.0001 을 "1E-4" 로 낸다 — KIS 는 이 값을 해석하지 못한다.
        assertThat(captureSellBody().get("BOND_ORD_UNPR")).isEqualTo("0.0001");
    }

    @Test
    @DisplayName("주문 수량이 0 이하면 KIS 를 부르기 전에 거부한다")
    void nonPositiveQuantity_isRejected() {
        BondSellRequest zeroQty = new BondSellRequest(BOND_CODE, "국고채권",
                BigDecimal.ZERO, new BigDecimal("10000"), "20260315", "3", Boolean.TRUE);

        assertThatThrownBy(() -> bondTradingService.sell(USER_ID, zeroQty))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("매도 성공 시 주문번호(ODNO)를 돌려준다")
    void sell_returnsOrderNumber() {
        givenLinkedUser();
        givenPostReturns(okOrderBody());

        Map<String, Object> result =
                bondTradingService.sell(USER_ID, sellRequest(new BigDecimal("10000"), Boolean.TRUE));

        assertThat(result.get("orderNumber")).isEqualTo("0000123456");
        assertThat(result.get("bondCode")).isEqualTo(BOND_CODE);
        assertThat(result.get("buyDate")).isEqualTo("20260315");
        assertThat(result.get("buySeq")).isEqualTo("3");
    }

    // ── Task 3: 거래내역 ────────────────────────────────────────────────

    @Test
    @DisplayName("거래내역 조회 실패는 빈 목록 + notice (조회 경로)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void historyKisFailure_degradesGracefully() {
        givenLinkedUser();
        given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(Map.class)))
                .willThrow(new ResourceAccessException("timeout"));

        BondTradeHistoryResponse result = bondTradingService.getHistory(USER_ID, null, null);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getNotice()).isNotBlank();
    }

    @Test
    @DisplayName("거래내역은 요청 기간을 그대로 KIS 로 넘긴다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void historyPassesRequestedDateRange() {
        givenLinkedUser();
        givenGetReturns(Map.of("rt_cd", "0", "output", List.of()));

        bondTradingService.getHistory(USER_ID, "20260101", "20260131");

        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(eq(BASE_URL), eq(KisBondClient.PATH_INQUIRE_DAILY_CCLD),
                eq(KisBondClient.TR_INQUIRE_DAILY_CCLD), anyString(), anyString(), anyString(),
                params.capture(), eq(Map.class));

        assertThat(params.getValue())
                .containsEntry("INQR_STRT_DT", "20260101")
                .containsEntry("INQR_END_DT", "20260131");
    }
}
