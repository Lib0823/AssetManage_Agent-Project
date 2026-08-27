package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.overseas.OverseasBalanceResponse;
import com.inbeom.apiserver.dto.overseas.OverseasOrderRequest;
import com.inbeom.apiserver.dto.overseas.OverseasOrderableResponse;
import com.inbeom.apiserver.dto.overseas.OverseasPendingOrderResponse;
import com.inbeom.apiserver.dto.overseas.OverseasTradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasTradingService 단위 테스트")
class OverseasTradingServiceTest {

    @Mock
    private KisAuthService kisAuthService;

    @Mock
    private KisApiClient kisApiClient;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OverseasTradingService overseasTradingService;

    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";

    private Long userId;
    private Long kisAccountId;
    private String mockKisToken;
    private KisCredentials mockCredentials;

    @BeforeEach
    void setUp() {
        userId = 1L;
        kisAccountId = 1L;
        mockKisToken = "MOCK_KIS_ACCESS_TOKEN";
        mockCredentials = credentials(BASE_URL);
    }

    private KisCredentials credentials(String baseUrl) {
        return new KisCredentials("MOCK_APP_KEY", "MOCK_APP_SECRET", "12345678", "01", baseUrl);
    }

    /**
     * userRepository.findById → KIS 계좌를 가진 User, 그리고 토큰/자격증명 스텁까지 한 번에 건다.
     */
    private void stubUserWithKisAccount(KisCredentials credentials) {
        UserKisAccount kisAccount = mock(UserKisAccount.class);
        User user = mock(User.class);
        when(user.getKisAccount()).thenReturn(kisAccount);
        when(kisAccount.getId()).thenReturn(kisAccountId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(credentials);
    }

    private void stubUserWithKisAccount() {
        stubUserWithKisAccount(mockCredentials);
    }

    /** KIS 계좌가 연결되지 않은 사용자. */
    private void stubUserWithoutKisAccount() {
        User user = mock(User.class);
        when(user.getKisAccount()).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private ResponseEntity<Map> body(Map<String, Object> map) {
        return new ResponseEntity<>(map, HttpStatus.OK);
    }

    private Map<String, Object> failedBody() {
        Map<String, Object> map = new HashMap<>();
        map.put("rt_cd", "1");
        map.put("msg1", "해외주식 거래 권한이 없습니다");
        return map;
    }

    private OverseasOrderRequest orderRequest(String symbol, String exchange, Integer qty, BigDecimal price) {
        return new OverseasOrderRequest(symbol, exchange, qty, price);
    }

    // ─── getBalance ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBalance - 미국 3거래소(NASD/NYSE/AMEX)를 순회하며 보유종목을 합산한다")
    void getBalance_AggregatesAcrossThreeExchanges() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> nasd = new HashMap<>();
        nasd.put("rt_cd", "0");
        nasd.put("output1", List.of(Map.of(
                "ovrs_pdno", "AAPL",
                "ovrs_item_name", "APPLE INC",
                "ovrs_cblc_qty", "10",
                "ord_psbl_qty", "10",
                "pchs_avg_pric", "150.00",
                "now_pric2", "195.00",
                "frcr_pchs_amt1", "1500.00",
                "ovrs_stck_evlu_amt", "1950.00",
                "frcr_evlu_pfls_amt", "450.00",
                "evlu_pfls_rt", "30.00"
        )));

        Map<String, Object> empty = new HashMap<>();
        empty.put("rt_cd", "0");
        empty.put("output1", List.of());

        when(kisApiClient.get(anyString(), eq("/uapi/overseas-stock/v1/trading/inquire-balance"),
                anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(body(nasd))
                .thenReturn(body(empty))
                .thenReturn(body(empty));

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getHoldings()).hasSize(1);
        assertThat(result.getHoldings().get(0).getSymbol()).isEqualTo("AAPL");
        assertThat(result.getHoldings().get(0).getQuantity()).isEqualTo(10);
        assertThat(result.getTotalPurchase()).isEqualByComparingTo("1500.00");
        assertThat(result.getTotalEval()).isEqualByComparingTo("1950.00");
        assertThat(result.getTotalProfitLoss()).isEqualByComparingTo("450.00");
        assertThat(result.getCurrency()).isEqualTo("USD");
        // 거래소 3곳을 모두 조회한다.
        verify(kisApiClient, times(3)).get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getBalance - 거래소마다 TTTS3012R 로 TR 을 보낸다")
    void getBalance_SendsRealTrId() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> ok = new HashMap<>();
        ok.put("rt_cd", "0");
        ok.put("output1", List.of());
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(ok));

        // When
        overseasTradingService.getBalance(userId);

        // Then
        verify(kisApiClient, times(3)).get(eq(BASE_URL), anyString(), eq("TTTS3012R"),
                anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getBalance - 보유수량 0 인 종목은 목록에서 제외한다")
    void getBalance_FiltersZeroQuantityHoldings() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> withZero = new HashMap<>();
        withZero.put("rt_cd", "0");
        withZero.put("output1", List.of(
                Map.of("ovrs_pdno", "AAPL", "ovrs_cblc_qty", "0", "frcr_pchs_amt1", "1500.00"),
                Map.of("ovrs_pdno", "TSLA", "ovrs_cblc_qty", "2", "frcr_pchs_amt1", "400.00",
                        "ovrs_stck_evlu_amt", "500.00", "frcr_evlu_pfls_amt", "100.00")
        ));
        Map<String, Object> empty = new HashMap<>();
        empty.put("rt_cd", "0");
        empty.put("output1", List.of());
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(body(withZero))
                .thenReturn(body(empty))
                .thenReturn(body(empty));

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getHoldings()).hasSize(1);
        assertThat(result.getHoldings().get(0).getSymbol()).isEqualTo("TSLA");
        // 제외된 종목의 매입금액은 합계에도 반영되지 않는다.
        assertThat(result.getTotalPurchase()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("getBalance - KIS 계좌가 없으면 빈 잔고 + notice 로 degrade")
    void getBalance_NoKisAccount_Degrades() {
        // Given
        stubUserWithoutKisAccount();

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getHoldings()).isEmpty();
        assertThat(result.getNotice()).isNotNull();
        assertThat(result.getTotalEval()).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getBalance - 사용자가 없으면 빈 잔고 + notice 로 degrade")
    void getBalance_UserNotFound_Degrades() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getNotice()).isNotNull();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getBalance - 3거래소 모두 rt_cd != 0 이면 notice 로 degrade (예외 전파 없음)")
    void getBalance_AllExchangesFail_Degrades() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failedBody()));

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getHoldings()).isEmpty();
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getBalance - 일부 거래소 호출이 예외를 던져도 나머지 거래소 결과로 응답한다")
    void getBalance_OneExchangeThrows_ContinuesWithOthers() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> ok = new HashMap<>();
        ok.put("rt_cd", "0");
        ok.put("output1", List.of(Map.of(
                "ovrs_pdno", "MSFT", "ovrs_cblc_qty", "3",
                "frcr_pchs_amt1", "900.00", "ovrs_stck_evlu_amt", "1200.00",
                "frcr_evlu_pfls_amt", "300.00")));
        Map<String, Object> empty = new HashMap<>();
        empty.put("rt_cd", "0");
        empty.put("output1", List.of());
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("read timed out"))
                .thenReturn(body(ok))
                .thenReturn(body(empty));

        // When
        OverseasBalanceResponse result = overseasTradingService.getBalance(userId);

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getHoldings()).hasSize(1);
        assertThat(result.getHoldings().get(0).getSymbol()).isEqualTo("MSFT");
    }

    // ─── getHistory ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHistory - 체결내역을 매핑하고 매도매수구분(01/02)을 SELL/BUY 로 정규화한다")
    void getHistory_Success() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", List.of(
                Map.of("odno", "ORD001", "pdno", "AAPL", "prdt_name", "APPLE INC",
                        "sll_buy_dvsn_cd", "02", "ft_ccld_qty", "10", "ft_ccld_unpr3", "195.50",
                        "ord_dt", "20260601", "ord_tmd", "093000", "prcs_stat_name", "체결"),
                Map.of("odno", "ORD002", "pdno", "TSLA", "prdt_name", "TESLA",
                        "sll_buy_dvsn_cd", "01", "ft_ccld_qty", "2", "ft_ccld_unpr3", "250.00",
                        "ord_dt", "20260602", "ord_tmd", "100000")
        ));
        when(kisApiClient.get(anyString(), eq("/uapi/overseas-stock/v1/trading/inquire-ccnl"),
                eq("TTTS3035R"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(body(response));

        // When
        OverseasTradeHistoryResponse result = overseasTradingService.getHistory(userId, "NASD");

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getList()).hasSize(2);
        assertThat(result.getList().get(0).getSide()).isEqualTo("BUY");
        assertThat(result.getList().get(0).getQty()).isEqualTo(10);
        assertThat(result.getList().get(0).getPrice()).isEqualByComparingTo("195.50");
        assertThat(result.getList().get(0).getExecutedAt()).isEqualTo("20260601093000");
        assertThat(result.getList().get(1).getSide()).isEqualTo("SELL");
    }

    @Test
    @DisplayName("getHistory - 조회기간(최근 90일)과 전체 구분('00')을 파라미터로 보낸다")
    void getHistory_SendsLookbackAndAllDivisionParams() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", List.of());
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        overseasTradingService.getHistory(userId, "NYSE");

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                paramsCaptor.capture(), eq(Map.class));
        Map<String, String> params = paramsCaptor.getValue();
        assertThat(params)
                .containsEntry("SLL_BUY_DVSN", "00")
                .containsEntry("CCLD_NCCS_DVSN", "00")
                .containsEntry("OVRS_EXCG_CD", "NYSE")
                .containsEntry("CANO", "12345678")
                .containsEntry("ACNT_PRDT_CD", "01");
        assertThat(params.get("ORD_STRT_DT")).hasSize(8);
        assertThat(params.get("ORD_END_DT")).hasSize(8);
    }

    @Test
    @DisplayName("getHistory - output 이 없으면 output1 을 대신 읽는다")
    void getHistory_FallsBackToOutput1() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output1", List.of(Map.of("odno", "ORD003", "pdno", "NVDA", "ccld_qty", "1")));
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        OverseasTradeHistoryResponse result = overseasTradingService.getHistory(userId, "NASD");

        // Then
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getSymbol()).isEqualTo("NVDA");
    }

    @Test
    @DisplayName("getHistory - rt_cd != 0 이면 빈 목록 + notice 로 degrade 한다")
    void getHistory_RtCdNotZero_Degrades() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failedBody()));

        // When
        OverseasTradeHistoryResponse result = overseasTradingService.getHistory(userId, "NASD");

        // Then
        assertThat(result.getList()).isEmpty();
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getHistory - KIS 호출 예외 시 빈 목록 + notice")
    void getHistory_ExceptionThrown_Degrades() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenThrow(new ResourceAccessException("connect timed out"));

        // When
        OverseasTradeHistoryResponse result = overseasTradingService.getHistory(userId, "NASD");

        // Then
        assertThat(result.getList()).isEmpty();
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getHistory - KIS 계좌가 없으면 KIS 호출 없이 degrade")
    void getHistory_NoKisAccount_Degrades() {
        // Given
        stubUserWithoutKisAccount();

        // When
        OverseasTradeHistoryResponse result = overseasTradingService.getHistory(userId, "NASD");

        // Then
        assertThat(result.getNotice()).isNotNull();
        verifyNoInteractions(kisApiClient);
    }

    // ─── getPendingOrders ───────────────────────────────────────────────────

    @Test
    @DisplayName("getPendingOrders - 미체결 내역을 매핑한다")
    void getPendingOrders_Success() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", List.of(Map.of(
                "odno", "ORD010", "pdno", "AAPL", "prdt_name", "APPLE INC",
                "sll_buy_dvsn_cd", "02", "ft_ord_qty", "10", "nccs_qty", "4",
                "ft_ord_unpr3", "190.00", "ord_dt", "20260605", "ord_tmd", "223000"
        )));
        when(kisApiClient.get(anyString(), eq("/uapi/overseas-stock/v1/trading/inquire-nccs"),
                eq("TTTS3018R"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(body(response));

        // When
        OverseasPendingOrderResponse result = overseasTradingService.getPendingOrders(userId, "NASD");

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getOrderNo()).isEqualTo("ORD010");
        assertThat(result.getList().get(0).getSide()).isEqualTo("BUY");
        assertThat(result.getList().get(0).getOrderQty()).isEqualTo(10);
        assertThat(result.getList().get(0).getRemainQty()).isEqualTo(4);
        assertThat(result.getList().get(0).getOrderPrice()).isEqualByComparingTo("190.00");
        assertThat(result.getList().get(0).getOrderedAt()).isEqualTo("20260605223000");
    }

    @Test
    @DisplayName("getPendingOrders - rt_cd != 0 이면 빈 목록 + notice")
    void getPendingOrders_RtCdNotZero_Degrades() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failedBody()));

        // When
        OverseasPendingOrderResponse result = overseasTradingService.getPendingOrders(userId, "NASD");

        // Then
        assertThat(result.getList()).isEmpty();
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getPendingOrders - KIS 계좌가 없으면 KIS 호출 없이 degrade")
    void getPendingOrders_NoKisAccount_Degrades() {
        // Given
        stubUserWithoutKisAccount();

        // When
        OverseasPendingOrderResponse result = overseasTradingService.getPendingOrders(userId, "NASD");

        // Then
        assertThat(result.getNotice()).isNotNull();
        verifyNoInteractions(kisApiClient);
    }

    // ─── getOrderable ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrderable - 매수가능 수량/금액을 매핑하고 지정단가를 그대로 전달한다")
    void getOrderable_Success() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", Map.of("max_ord_psbl_qty", "25", "ord_psbl_frcr_amt", "5000.00"));
        when(kisApiClient.get(anyString(), eq("/uapi/overseas-stock/v1/trading/inquire-psamount"),
                eq("TTTS3007R"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(body(response));

        // When
        OverseasOrderableResponse result =
                overseasTradingService.getOrderable(userId, "AAPL", "NASD", new BigDecimal("195.50"));

        // Then
        assertThat(result.getNotice()).isNull();
        assertThat(result.getSymbol()).isEqualTo("AAPL");
        assertThat(result.getMaxBuyQty()).isEqualTo(25);
        assertThat(result.getOrderableCash()).isEqualByComparingTo("5000.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                paramsCaptor.capture(), eq(Map.class));
        assertThat(paramsCaptor.getValue())
                .containsEntry("OVRS_ORD_UNPR", "195.50")
                .containsEntry("ITEM_CD", "AAPL")
                .containsEntry("OVRS_EXCG_CD", "NASD");
    }

    @Test
    @DisplayName("getOrderable - 단가가 null/0/음수면 OVRS_ORD_UNPR 을 '0' 으로 보낸다")
    void getOrderable_NullOrNonPositivePrice_SendsZeroUnitPrice() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", Map.of("max_ord_psbl_qty", "0"));
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        overseasTradingService.getOrderable(userId, "AAPL", "NASD", null);
        overseasTradingService.getOrderable(userId, "AAPL", "NASD", BigDecimal.ZERO);
        overseasTradingService.getOrderable(userId, "AAPL", "NASD", new BigDecimal("-5"));

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient, times(3)).get(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), paramsCaptor.capture(), eq(Map.class));
        assertThat(paramsCaptor.getAllValues())
                .allSatisfy(params -> assertThat(params).containsEntry("OVRS_ORD_UNPR", "0"));
    }

    @Test
    @DisplayName("getOrderable - 응답 필드명이 변형(ord_psbl_qty/ovrs_ord_psbl_amt)돼도 후보 키로 찾는다")
    void getOrderable_AlternateFieldNames_StillMapped() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        response.put("output", Map.of("ord_psbl_qty", "7", "ovrs_ord_psbl_amt", "1400.00"));
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        OverseasOrderableResponse result =
                overseasTradingService.getOrderable(userId, "AAPL", "NASD", new BigDecimal("200"));

        // Then
        assertThat(result.getMaxBuyQty()).isEqualTo(7);
        assertThat(result.getOrderableCash()).isEqualByComparingTo("1400.00");
    }

    @Test
    @DisplayName("getOrderable - rt_cd != 0 이면 0 + notice 로 degrade")
    void getOrderable_RtCdNotZero_Degrades() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(failedBody()));

        // When
        OverseasOrderableResponse result =
                overseasTradingService.getOrderable(userId, "AAPL", "NASD", new BigDecimal("200"));

        // Then
        assertThat(result.getMaxBuyQty()).isZero();
        assertThat(result.getOrderableCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getOrderable - output 이 없으면 0 + notice 로 degrade")
    void getOrderable_MissingOutput_Degrades() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> response = new HashMap<>();
        response.put("rt_cd", "0");
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class))).thenReturn(body(response));

        // When
        OverseasOrderableResponse result =
                overseasTradingService.getOrderable(userId, "AAPL", "NASD", new BigDecimal("200"));

        // Then
        assertThat(result.getNotice()).isNotNull();
    }

    @Test
    @DisplayName("getOrderable - KIS 계좌가 없으면 요청한 거래소를 유지한 채 0 + notice")
    void getOrderable_NoKisAccount_Degrades() {
        // Given
        stubUserWithoutKisAccount();

        // When
        OverseasOrderableResponse result =
                overseasTradingService.getOrderable(userId, "AAPL", "NYSE", new BigDecimal("200"));

        // Then
        assertThat(result.getExchange()).isEqualTo("NYSE");
        assertThat(result.getMaxBuyQty()).isZero();
        assertThat(result.getNotice()).isNotNull();
        verifyNoInteractions(kisApiClient);
    }

    // ─── buy / sell ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("buy - TTTT1002U 지정가 주문을 보내고 주문번호를 반환한다")
    void buy_Success() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        kisResponse.put("output", Map.of("ODNO", "OVS123456"));
        when(kisApiClient.post(eq(BASE_URL), eq("/uapi/overseas-stock/v1/trading/order"),
                eq("TTTT1002U"), eq(mockKisToken), eq("MOCK_APP_KEY"), eq("MOCK_APP_SECRET"),
                any(), eq(Map.class))).thenReturn(body(kisResponse));

        // When
        Map<String, Object> result = overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 10, new BigDecimal("195.50")));

        // Then
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("orderNumber")).isEqualTo("OVS123456");
        assertThat(result.get("symbol")).isEqualTo("AAPL");
        assertThat(result.get("exchange")).isEqualTo("NASD");
        assertThat(result.get("quantity")).isEqualTo(10);
        assertThat(result.get("orderType")).isEqualTo("BUY");
    }

    @Test
    @DisplayName("buy - 주문 본문에 지정가(ORD_DVSN=00)와 단가/수량이 담긴다")
    void buy_SendsLimitOrderBody() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        kisResponse.put("output", Map.of("ODNO", "OVS1"));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class))).thenReturn(body(kisResponse));

        // When
        overseasTradingService.buy(userId, orderRequest("AAPL", "NASD", 3, new BigDecimal("190.25")));

        // Then
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kisApiClient).post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                bodyCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(requestBody)
                .containsEntry("ORD_DVSN", "00")
                .containsEntry("ORD_QTY", "3")
                .containsEntry("OVRS_ORD_UNPR", "190.25")
                .containsEntry("OVRS_EXCG_CD", "NASD")
                .containsEntry("PDNO", "AAPL")
                .containsEntry("CANO", "12345678")
                .containsEntry("SLL_TYPE", "");  // 매수는 빈 값
    }

    @Test
    @DisplayName("sell - TTTT1006U 로 보내고 SLL_TYPE='00' 을 채운다")
    void sell_Success() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        kisResponse.put("output", Map.of("ODNO", "OVS999"));
        when(kisApiClient.post(anyString(), anyString(), eq("TTTT1006U"), anyString(), anyString(),
                anyString(), any(), eq(Map.class))).thenReturn(body(kisResponse));

        // When
        Map<String, Object> result = overseasTradingService.sell(
                userId, orderRequest("TSLA", "NASD", 2, new BigDecimal("250.00")));

        // Then
        assertThat(result.get("orderType")).isEqualTo("SELL");
        assertThat(result.get("orderNumber")).isEqualTo("OVS999");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kisApiClient).post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                bodyCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(requestBody).containsEntry("SLL_TYPE", "00");
    }

    @Test
    @DisplayName("buy - 수량이 null/0/음수면 INVALID_TRADE_QUANTITY(5002)로 KIS 호출 전에 막는다")
    void buy_InvalidQuantity_ThrowsInvalidTradeQuantity() {
        // Given / When / Then
        for (Integer invalid : new Integer[]{null, 0, -1}) {
            assertThatThrownBy(() -> overseasTradingService.buy(
                    userId, orderRequest("AAPL", "NASD", invalid, new BigDecimal("195.50"))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TRADE_QUANTITY);
        }

        verifyNoInteractions(kisApiClient);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("buy - 단가가 null/0/음수면 INVALID_TRADE_PRICE(5003) (해외는 지정가 전용)")
    void buy_InvalidPrice_ThrowsInvalidTradePrice() {
        // Given / When / Then
        BigDecimal[] invalidPrices = {null, BigDecimal.ZERO, new BigDecimal("-1")};
        for (BigDecimal invalid : invalidPrices) {
            assertThatThrownBy(() -> overseasTradingService.buy(
                    userId, orderRequest("AAPL", "NASD", 10, invalid)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TRADE_PRICE);
        }

        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("sell - 수량 검증은 매도에도 동일하게 적용된다")
    void sell_InvalidQuantity_ThrowsInvalidTradeQuantity() {
        // Given / When / Then
        assertThatThrownBy(() -> overseasTradingService.sell(
                userId, orderRequest("AAPL", "NASD", 0, new BigDecimal("195.50"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TRADE_QUANTITY);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("buy - 사용자가 없으면 USER_NOT_FOUND(3000) 예외")
    void buy_UserNotFound_Throws() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 10, new BigDecimal("195.50"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("buy - KIS 계좌 미등록이면 KIS_ACCOUNT_NOT_FOUND(4000) 예외 (조회와 달리 삼키지 않는다)")
    void buy_NoKisAccount_Throws() {
        // Given
        stubUserWithoutKisAccount();

        // When / Then
        assertThatThrownBy(() -> overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 10, new BigDecimal("195.50"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_ACCOUNT_NOT_FOUND);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("buy - KIS 응답 rt_cd != 0 이면 KIS_API_SERVER_ERROR(4002) 예외 (200 으로 내려가지 않는다)")
    void buy_RtCdNotZero_ThrowsKisApiException() {
        // Given: 해외매매 권한 미보유도 이 경로로 내려온다.
        stubUserWithKisAccount();
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class))).thenReturn(body(failedBody()));

        // When / Then
        assertThatThrownBy(() -> overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 10, new BigDecimal("195.50"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_API_SERVER_ERROR)
                .hasMessageContaining("해외주식 거래 권한이 없습니다");
    }

    @Test
    @DisplayName("buy - KIS 응답 body 가 비어 있으면 KIS_API_SERVER_ERROR(4002) 예외")
    void buy_EmptyBody_ThrowsKisApiException() {
        // Given
        stubUserWithKisAccount();
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class))).thenReturn(new ResponseEntity<>((Map<String, Object>) null, HttpStatus.OK));

        // When / Then
        assertThatThrownBy(() -> overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 10, new BigDecimal("195.50"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_API_SERVER_ERROR);
    }

    @Test
    @DisplayName("buy - 미지원 거래소 코드는 NASD 로 폴백해 주문한다")
    void buy_UnknownExchange_FallsBackToNasd() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        kisResponse.put("output", Map.of("ODNO", "OVS_FB"));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class))).thenReturn(body(kisResponse));

        // When
        Map<String, Object> result = overseasTradingService.buy(
                userId, orderRequest("AAPL", "HKEX", 1, new BigDecimal("100")));

        // Then
        assertThat(result.get("exchange")).isEqualTo("NASD");
    }

    @Test
    @DisplayName("buy - output 에 ODNO 가 없어도 성공 응답을 만든다 (orderNumber=null)")
    void buy_MissingOrderNumber_StillSucceeds() {
        // Given
        stubUserWithKisAccount();
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class))).thenReturn(body(kisResponse));

        // When
        Map<String, Object> result = overseasTradingService.buy(
                userId, orderRequest("AAPL", "NASD", 1, new BigDecimal("100")));

        // Then
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("orderNumber")).isNull();
    }
}
