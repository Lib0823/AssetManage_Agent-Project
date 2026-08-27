package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.TradeHistory;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.kis.KisBalanceResponse;
import com.inbeom.apiserver.dto.kis.KisDailyCcldResponse;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.dto.trade.OrderableResponse;
import com.inbeom.apiserver.dto.trade.PendingOrderResponse;
import com.inbeom.apiserver.dto.trade.PlaceReservedOrderRequest;
import com.inbeom.apiserver.dto.trade.RecentTradeResponse;
import com.inbeom.apiserver.dto.trade.ReservedOrderResponse;
import com.inbeom.apiserver.dto.trade.ReservedOrderResultResponse;
import com.inbeom.apiserver.dto.trade.TradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.TradeExecutionPlanRepository;
import com.inbeom.apiserver.repository.TradeHistoryRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingService 단위 테스트")
class TradingServiceTest {

    @Mock
    private KisAuthService kisAuthService;

    @Mock
    private KisApiClient kisApiClient;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TradeExecutionPlanRepository tradeExecutionPlanRepository;

    @InjectMocks
    private TradingService tradingService;

    private User mockUser;
    private KisCredentials mockCredentials;
    private String mockKisToken;
    private Long userId;
    private Long kisAccountId;

    @BeforeEach
    void setUp() {
        userId = 1L;
        kisAccountId = 1L;
        mockKisToken = "MOCK_KIS_ACCESS_TOKEN";
        mockCredentials = new KisCredentials(
                "MOCK_APP_KEY",
                "MOCK_APP_SECRET",
                "12345678-01",
                "01",
                "https://openapi.koreainvestment.com:9443"
        );

        mockUser = User.builder()
                .id(userId)
                .username("testuser")
                .email("test@example.com")
                .name("Test User")
                .build();
    }

    @Test
    @DisplayName("executeBuy - 매수 주문 실행 (KIS 주문 후 응답 반환, DB 미저장)")
    void executeBuy_Success() {
        // Given: 거래내역은 DB에 저장하지 않고 KIS(TTTC0802U) 주문 응답을 그대로 반환한다.
        String stockCode = "005930";
        String stockName = "삼성전자";
        Integer quantity = 10;
        BigDecimal orderPrice = new BigDecimal("70000");

        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER123456"));
        kisResponse.put("rt_cd", "0");
        kisResponse.put("msg1", "주문이 완료되었습니다.");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.post(
                anyString(),
                eq("/uapi/domestic-stock/v1/trading/order-cash"),
                eq("TTTC0802U"),
                eq(mockKisToken),
                eq("MOCK_APP_KEY"),
                eq("MOCK_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, stockCode, stockName, quantity, orderPrice);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("rt_cd")).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        assertThat(output.get("ODNO")).isEqualTo("ORDER123456");
        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), eq("TTTC0802U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("executeSell - 매도 주문 실행 (KIS 주문 후 응답 반환, DB 미저장)")
    void executeSell_Success() {
        // Given
        String stockCode = "005930";
        String stockName = "삼성전자";
        Integer quantity = 5;
        BigDecimal orderPrice = new BigDecimal("75000");

        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER789012"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.post(
                anyString(),
                eq("/uapi/domestic-stock/v1/trading/order-cash"),
                eq("TTTC0801U"),
                anyString(),
                anyString(),
                anyString(),
                anyMap(),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeSell(userId, kisAccountId, stockCode, stockName, quantity, orderPrice);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("rt_cd")).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        assertThat(output.get("ODNO")).isEqualTo("ORDER789012");
        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), eq("TTTC0801U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getTradeHistory - 사용자 거래 내역 조회 성공 (KIS inquire-daily-ccld 직접 조회)")
    void getTradeHistory_Success() {
        // Given: 거래내역은 DB가 아니라 KIS API(TTTC0081R)를 직접 조회해 TradeHistoryResponse 로 매핑한다.
        UserKisAccount kisAccount = mock(UserKisAccount.class);
        User userWithKis = mock(User.class);
        when(userWithKis.getKisAccount()).thenReturn(kisAccount);
        when(kisAccount.getId()).thenReturn(kisAccountId);

        KisDailyCcldResponse.DailyCcldItem item1 = new KisDailyCcldResponse.DailyCcldItem();
        item1.setOdno("ORDER001");
        item1.setPdno("005930");
        item1.setPrdtName("삼성전자");
        item1.setSllBuyDvsnCd("02");  // 02: 매수 → BUY
        item1.setOrdDt("20260601");
        item1.setOrdTmd("093000");
        item1.setOrdQty("10");
        item1.setOrdUnpr("70000");
        item1.setTotCcldQty("10");
        item1.setTotCcldAmt("700000");
        item1.setAvgPrvs("70000");

        KisDailyCcldResponse.DailyCcldItem item2 = new KisDailyCcldResponse.DailyCcldItem();
        item2.setOdno("ORDER002");
        item2.setPdno("000660");
        item2.setPrdtName("SK하이닉스");
        item2.setSllBuyDvsnCd("01");  // 01: 매도 → SELL
        item2.setOrdDt("20260601");
        item2.setOrdTmd("100500");
        item2.setOrdQty("5");
        item2.setOrdUnpr("120000");
        item2.setTotCcldQty("5");
        item2.setTotCcldAmt("600000");
        item2.setAvgPrvs("120000");

        KisDailyCcldResponse kisResponse = new KisDailyCcldResponse();
        kisResponse.setRtCd("0");
        kisResponse.setOutput1(List.of(item1, item2));

        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithKis));
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisDailyCcldResponse.class)
        )).thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getOrderType()).isEqualTo("BUY");
        assertThat(result.get(0).getId()).isEqualTo("ORDER001");
        assertThat(result.get(1).getStockCode()).isEqualTo("000660");
        assertThat(result.get(1).getOrderType()).isEqualTo("SELL");

        verify(kisApiClient, times(1)).get(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisDailyCcldResponse.class));
    }

    @Test
    @DisplayName("executeBuy - KIS 응답의 주문번호(ODNO)가 반환 결과에 포함된다")
    void executeBuy_ReturnsOrderNumber() {
        // Given
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER999888"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000"));

        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        assertThat(output.get("ODNO")).isEqualTo("ORDER999888");
    }

    /**
     * userRepository.findById → KIS 계좌를 가진 User 를 돌려주도록 스텁한다.
     * getOrderable 이 userId → kisAccountId 를 해석하는 경로에서만 필요하다.
     */
    private void stubUserWithKisAccount() {
        UserKisAccount kisAccount = mock(UserKisAccount.class);
        User userWithKis = mock(User.class);
        when(userWithKis.getKisAccount()).thenReturn(kisAccount);
        when(kisAccount.getId()).thenReturn(kisAccountId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithKis));
    }

    private ResponseEntity<Map> orderableResponse(String rtCd, String maxBuyQty) {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", rtCd);
        body.put("msg1", "조회 결과");
        body.put("output", Map.of("max_buy_qty", maxBuyQty, "ord_psbl_cash", "1000000"));
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    @Test
    @DisplayName("executeBuy - 시장가(orderPrice=null)는 매수가능조회를 건너뛴다")
    void executeBuy_MarketOrder_SkipsOrderableLookup() {
        // Given: getOrderable 은 항상 ORD_DVSN="00"(지정가) 기준이라, 시장가에 price=0 으로 물으면
        // max_buy_qty=0 이 정상 응답으로 와 모든 시장가 매수가 차단될 수 있다 → 조회를 건너뛰어야 한다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER_MARKET"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, null);

        // Then: 주문은 나가고, 매수가능조회(GET)는 한 번도 호출되지 않는다.
        assertThat(result.get("rt_cd")).isEqualTo("0");
        verify(kisApiClient, never()).get(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("executeBuy - 시장가(orderPrice=0)도 매수가능조회를 건너뛴다")
    void executeBuy_ZeroPrice_SkipsOrderableLookup() {
        // Given: ai-agent Stage 6 는 시장가 매수를 price=0 으로 보낸다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER_ZERO"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, BigDecimal.ZERO);

        // Then
        assertThat(result.get("rt_cd")).isEqualTo("0");
        verify(kisApiClient, never()).get(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("executeBuy - 매수가능조회가 degrade(notice!=null)되면 fail-open 으로 주문을 통과시킨다")
    void executeBuy_OrderableDegraded_FailsOpen() {
        // Given: KIS rt_cd != 0 → getOrderable 이 notice 를 채운 degrade 응답을 반환한다.
        // 조회 실패를 잔고 부족으로 오인해 정상 주문을 막으면 안 된다 (최종 판정은 KIS 가 한다).
        stubUserWithKisAccount();

        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER_FAILOPEN"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(orderableResponse("1", "0"));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When: max_buy_qty=0 이지만 notice 가 있으므로 차단하지 않는다.
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000"));

        // Then
        assertThat(result.get("rt_cd")).isEqualTo("0");
        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), eq("TTTC0802U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("executeBuy - 최대매수수량 < 요청수량이면 INSUFFICIENT_BALANCE(5001)로 주문 전에 막는다")
    void executeBuy_ExceedsMaxBuyQuantity_ThrowsInsufficientBalance() {
        // Given: 정상 조회(rt_cd=0)에서 max_buy_qty=5 인데 10주를 요청한다.
        stubUserWithKisAccount();

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(orderableResponse("0", "5"));

        // When / Then
        assertThatThrownBy(() ->
                tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE);

        // 주문은 KIS 로 나가지 않아야 한다 (주문은 부작용이 있으므로 사전 차단이 핵심).
        verify(kisApiClient, never()).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("executeBuy - 최대매수수량 >= 요청수량이면 주문이 통과한다")
    void executeBuy_WithinMaxBuyQuantity_Proceeds() {
        // Given
        stubUserWithKisAccount();

        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("output", Map.of("ODNO", "ORDER_OK"));
        kisResponse.put("rt_cd", "0");

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(orderableResponse("0", "10"));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(kisResponse, HttpStatus.OK));

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000"));

        // Then
        assertThat(result.get("rt_cd")).isEqualTo("0");
    }

    @Test
    @DisplayName("executeBuy - 수량이 0/음수/null 이면 INVALID_TRADE_QUANTITY(5002)로 KIS 호출 전에 막는다")
    void executeBuy_InvalidQuantity_ThrowsInvalidTradeQuantity() {
        // Given: web-app 은 @Min(1) 로 걸러지지만 ai-agent 내부 경로는 수량을 그대로 위임한다.
        // When / Then
        for (Integer invalidQuantity : new Integer[]{0, -1, null}) {
            assertThatThrownBy(() ->
                    tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", invalidQuantity, new BigDecimal("70000")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TRADE_QUANTITY);
        }

        // 자격증명 조회조차 하지 않고 끊긴다.
        verifyNoInteractions(kisApiClient);
        verifyNoInteractions(kisAuthService);
    }

    // ==================================================================
    // 이하: 분기 커버리지 보강 (에러 처리 / graceful degrade / 매핑 규칙)
    // ==================================================================

    /** KIS 자격증명·토큰 스텁 (kisAccountId 를 이미 아는 경로용). */
    private void stubKisAuth() {
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
    }

    private KisDailyCcldResponse.DailyCcldItem ccldItem(String odno, String sllBuyDvsnCd,
                                                        String ordQty, String totCcldQty) {
        KisDailyCcldResponse.DailyCcldItem item = new KisDailyCcldResponse.DailyCcldItem();
        item.setOdno(odno);
        item.setPdno("005930");
        item.setPrdtName("삼성전자");
        item.setSllBuyDvsnCd(sllBuyDvsnCd);
        item.setOrdDt("20260601");
        item.setOrdTmd("093000");
        item.setOrdQty(ordQty);
        item.setOrdUnpr("70000");
        item.setTotCcldQty(totCcldQty);
        item.setAvgPrvs("70000");
        return item;
    }

    private void stubCcldGet(KisDailyCcldResponse body) {
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisDailyCcldResponse.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void stubMapGet(Map<String, Object> body) {
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void stubMapPost(Map<String, Object> body) {
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    // ================== getRecentTrades ==================

    @Test
    @DisplayName("getRecentTrades - 최신순 최대 8건까지만 매핑해 반환한다")
    void getRecentTrades_LimitsToEight() {
        // Given: 홈 알림 영역은 8건까지만 보여준다.
        List<TradeHistory> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(TradeHistory.builder()
                    .id((long) i)
                    .stockCode("00593" + i)
                    .stockName("종목" + i)
                    .orderType(i % 2 == 0 ? "BUY" : "SELL")
                    .orderStatus("COMPLETED")
                    .quantity(i + 1)
                    .orderPrice(new BigDecimal("70000"))
                    .executedPrice(new BigDecimal("70100"))
                    .orderedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                    .build());
        }
        when(tradeHistoryRepository.findByUserIdOrderByOrderedAtDesc(userId)).thenReturn(rows);

        // When
        List<RecentTradeResponse> result = tradingService.getRecentTrades(userId);

        // Then
        assertThat(result).hasSize(8);
        assertThat(result.get(0).getId()).isEqualTo(0L);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getStockName()).isEqualTo("종목0");
        assertThat(result.get(0).getOrderType()).isEqualTo("BUY");
        assertThat(result.get(0).getOrderStatus()).isEqualTo("COMPLETED");
        assertThat(result.get(0).getQuantity()).isEqualTo(1);
        assertThat(result.get(0).getOrderPrice()).isEqualByComparingTo("70000");
        assertThat(result.get(0).getExecutedPrice()).isEqualByComparingTo("70100");
        assertThat(result.get(0).getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 9, 0));
    }

    @Test
    @DisplayName("getRecentTrades - DB 조회 실패 시 예외를 삼키고 빈 목록을 반환한다")
    void getRecentTrades_RepositoryFails_ReturnsEmptyList() {
        // Given: 홈 화면 부가 정보이므로 DB 장애가 화면 전체를 깨뜨리면 안 된다.
        when(tradeHistoryRepository.findByUserIdOrderByOrderedAtDesc(userId))
                .thenThrow(new RuntimeException("DB down"));

        // When
        List<RecentTradeResponse> result = tradingService.getRecentTrades(userId);

        // Then
        assertThat(result).isEmpty();
    }

    // ================== executeBuy / executeSell 에러 경로 ==================

    @Test
    @DisplayName("executeBuy - KIS가 rt_cd!=0 으로 거부하면 KIS 사유를 담아 예외를 던진다")
    void executeBuy_KisRejects_ThrowsWithKisMessage() {
        // Given: KIS 는 주문 거부도 HTTP 200 + rt_cd="1" 로 준다. 검사하지 않으면 실패가 성공으로 보고된다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "1");
        kisResponse.put("msg1", "  영업일이 아닙니다.  ");

        stubKisAuth();
        stubMapPost(kisResponse);

        // When / Then
        assertThatThrownBy(() ->
                tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 10, null))
                .isInstanceOf(KisApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_API_SERVER_ERROR)
                .hasMessageContaining("영업일이 아닙니다.")
                .hasMessageContaining("rt_cd=1");
    }

    @Test
    @DisplayName("executeBuy - KIS 응답 바디가 비어 있으면 예외를 던진다")
    void executeBuy_NullResponseBody_Throws() {
        // Given
        stubKisAuth();
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(HttpStatus.OK));

        // When / Then
        assertThatThrownBy(() ->
                tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 1, null))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS 주문 응답이 비어 있습니다.");
    }

    @Test
    @DisplayName("executeBuy - output 이 없는 성공 응답도 그대로 반환한다(주문번호는 null)")
    void executeBuy_SuccessWithoutOutput_ReturnsBody() {
        // Given: rt_cd=0 이지만 output 이 없는 응답에서 주문번호 추출이 NPE 로 터지면 안 된다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");

        stubKisAuth();
        stubMapPost(kisResponse);

        // When
        Map<String, Object> result = tradingService.executeBuy(userId, kisAccountId, "005930", "삼성전자", 1, null);

        // Then
        assertThat(result).containsEntry("rt_cd", "0");
        assertThat(result).doesNotContainKey("output");
    }

    @Test
    @DisplayName("executeSell - 수량이 0/음수/null 이면 INVALID_TRADE_QUANTITY(5002)로 KIS 호출 전에 막는다")
    void executeSell_InvalidQuantity_ThrowsInvalidTradeQuantity() {
        // Given / When / Then
        for (Integer invalidQuantity : new Integer[]{0, -3, null}) {
            assertThatThrownBy(() ->
                    tradingService.executeSell(userId, kisAccountId, "005930", "삼성전자", invalidQuantity, new BigDecimal("70000")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TRADE_QUANTITY);
        }

        verifyNoInteractions(kisApiClient);
        verifyNoInteractions(kisAuthService);
    }

    @Test
    @DisplayName("executeSell - KIS 거부 시 msg1 이 없으면 기본 메시지로 예외를 던진다")
    void executeSell_KisRejectsWithoutMessage_ThrowsDefaultMessage() {
        // Given: 보유수량 초과 등의 판정은 KIS 가 하며, rt_cd!=0 은 그대로 실패로 전파돼야 한다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "1");

        stubKisAuth();
        stubMapPost(kisResponse);

        // When / Then
        assertThatThrownBy(() ->
                tradingService.executeSell(userId, kisAccountId, "005930", "삼성전자", 5, new BigDecimal("70000")))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS 주문이 거부되었습니다.");
    }

    @Test
    @DisplayName("executeSell - 매도는 매수여력 검증 없이 곧바로 주문한다")
    void executeSell_DoesNotCheckBuyingPower() {
        // Given: verifyBuyingPower 는 매수 전용이다. 매도에서 매수가능조회를 호출하면 불필요한 KIS 콜이 된다.
        Map<String, Object> kisResponse = new HashMap<>();
        kisResponse.put("rt_cd", "0");
        kisResponse.put("output", Map.of("ODNO", "SELL_ONLY"));

        stubKisAuth();
        stubMapPost(kisResponse);

        // When
        tradingService.executeSell(userId, kisAccountId, "005930", "삼성전자", 5, new BigDecimal("70000"));

        // Then
        verify(kisApiClient, never()).get(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    // ================== getTradeHistory ==================

    @Test
    @DisplayName("getTradeHistory - 사용자를 찾지 못하면 UserNotFoundException 을 던진다")
    void getTradeHistory_UserNotFound_Throws() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> tradingService.getTradeHistory(userId))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getTradeHistory - KIS 응답 바디가 null 이면 빈 목록을 반환한다")
    void getTradeHistory_NullBody_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisDailyCcldResponse.class)))
                .thenReturn(new ResponseEntity<KisDailyCcldResponse>(HttpStatus.OK));

        // When / Then
        assertThat(tradingService.getTradeHistory(userId)).isEmpty();
    }

    @Test
    @DisplayName("getTradeHistory - output1 이 null 이면 빈 목록을 반환한다")
    void getTradeHistory_NullOutput1_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        stubCcldGet(body);

        // When / Then
        assertThat(tradingService.getTradeHistory(userId)).isEmpty();
    }

    @Test
    @DisplayName("getTradeHistory - (주문일자|주문번호)가 일치하는 봇 주문만 aiTraded=true 로 표시한다")
    void getTradeHistory_MarksAiTradedByDateAndOrderNumber() {
        // Given: ODNO 는 당일 채번이라 날짜까지 함께 매칭해야 다른 날 동일 ODNO 오매칭을 막는다.
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse.DailyCcldItem botOrder = ccldItem("ORDER001", "02", "10", "10");
        KisDailyCcldResponse.DailyCcldItem manualOrder = ccldItem("ORDER002", "01", "5", "5");
        // 같은 ODNO 지만 날짜가 달라 매칭되면 안 되는 주문
        KisDailyCcldResponse.DailyCcldItem otherDaySameOdno = ccldItem("ORDER001", "02", "3", "3");
        otherDaySameOdno.setOrdDt("20260515");

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(botOrder, manualOrder, otherDaySameOdno));
        stubCcldGet(body);

        when(tradeExecutionPlanRepository.findExecutedOrderKeys(userId))
                .thenReturn(Set.of("2026-06-01|ORDER001"));

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getAiTraded()).isTrue();
        assertThat(result.get(1).getAiTraded()).isFalse();
        assertThat(result.get(2).getAiTraded()).isFalse();
    }

    @Test
    @DisplayName("getTradeHistory - 봇 주문키 조회가 실패해도 거래내역 자체는 정상 반환한다")
    void getTradeHistory_AiKeyLookupFails_DegradesGracefully() {
        // Given: AI 배지는 비핵심 정보이므로 조회 실패는 빈 집합으로 degrade 한다.
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(ccldItem("ORDER001", "02", "10", "10")));
        stubCcldGet(body);

        when(tradeExecutionPlanRepository.findExecutedOrderKeys(userId))
                .thenThrow(new RuntimeException("DB down"));

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAiTraded()).isFalse();
    }

    @Test
    @DisplayName("getTradeHistory - 체결 상태를 CANCELLED/PENDING/PARTIAL/COMPLETED 로 판정한다")
    void getTradeHistory_MapsOrderStatuses() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse.DailyCcldItem cancelled = ccldItem("O1", "02", "10", "0");
        cancelled.setCnclYn("Y");
        KisDailyCcldResponse.DailyCcldItem pending = ccldItem("O2", "02", "10", "0");
        KisDailyCcldResponse.DailyCcldItem partial = ccldItem("O3", "02", "10", "4");
        KisDailyCcldResponse.DailyCcldItem completed = ccldItem("O4", "01", "10", "10");

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(cancelled, pending, partial, completed));
        stubCcldGet(body);

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result).extracting(TradeHistoryResponse::getOrderStatus)
                .containsExactly("CANCELLED", "PENDING", "PARTIAL", "COMPLETED");
        assertThat(result).extracting(TradeHistoryResponse::getOrderType)
                .containsExactly("BUY", "BUY", "BUY", "SELL");
        assertThat(result.get(2).getExecutedQuantity()).isEqualTo(4);
        assertThat(result.get(0).getOrderDate()).isEqualTo("2026-06-01");
        assertThat(result.get(0).getOrderTime()).isEqualTo("09:30:00");
    }

    @Test
    @DisplayName("getTradeHistory - 주문시각이 없거나 6자리 미만이어도 안전하게 파싱한다")
    void getTradeHistory_HandlesMissingAndShortOrderTime() {
        // Given: KIS 가 ord_tmd 를 "93000"(앞자리 0 누락)으로 주거나 아예 비워 보내는 경우가 있다.
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse.DailyCcldItem noTime = ccldItem("O1", "02", "10", "10");
        noTime.setOrdTmd(null);
        KisDailyCcldResponse.DailyCcldItem emptyTime = ccldItem("O2", "02", "10", "10");
        emptyTime.setOrdTmd("");
        KisDailyCcldResponse.DailyCcldItem shortTime = ccldItem("O3", "02", "10", "10");
        shortTime.setOrdTmd("93000");

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(noTime, emptyTime, shortTime));
        stubCcldGet(body);

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result.get(0).getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThat(result.get(1).getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThat(result.get(2).getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 9, 30));
    }

    @Test
    @DisplayName("getTradeHistory - 파싱 불가한 수량/가격은 0 으로 보정한다")
    void getTradeHistory_UnparsableNumbers_FallBackToZero() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse.DailyCcldItem broken = ccldItem("O1", "02", "abc", "  ");
        broken.setOrdUnpr("N/A");
        broken.setAvgPrvs(null);

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(broken));
        stubCcldGet(body);

        // When
        List<TradeHistoryResponse> result = tradingService.getTradeHistory(userId);

        // Then
        assertThat(result.get(0).getQuantity()).isZero();
        assertThat(result.get(0).getExecutedQuantity()).isZero();
        assertThat(result.get(0).getOrderPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.get(0).getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ================== getPendingOrders ==================

    @Test
    @DisplayName("getPendingOrders - 취소/전량체결을 제외하고 미체결 행만 반환한다")
    void getPendingOrders_FiltersOnlyUnfilled() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisDailyCcldResponse.DailyCcldItem cancelled = ccldItem("O1", "02", "10", "0");
        cancelled.setCnclYn("Y");
        cancelled.setRmnQty("10");

        KisDailyCcldResponse.DailyCcldItem partial = ccldItem("O2", "02", "10", "7");
        partial.setRmnQty("3");

        KisDailyCcldResponse.DailyCcldItem filled = ccldItem("O3", "02", "10", "10");
        filled.setRmnQty("0");

        // rmn_qty 미제공 → 주문수량 - 총체결수량으로 보정
        KisDailyCcldResponse.DailyCcldItem noRmn = ccldItem("O4", "01", "10", "4");

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(cancelled, partial, filled, noRmn));
        stubCcldGet(body);

        // When
        List<PendingOrderResponse> result = tradingService.getPendingOrders(userId);

        // Then
        assertThat(result).extracting(PendingOrderResponse::getOrderNumber).containsExactly("O2", "O4");
        assertThat(result.get(0).getOrderType()).isEqualTo("BUY");
        assertThat(result.get(0).getOrderQuantity()).isEqualTo(10);
        assertThat(result.get(0).getRemainQuantity()).isEqualTo(3);
        assertThat(result.get(0).getOrderPrice()).isEqualByComparingTo("70000");
        assertThat(result.get(0).getOrderedAt()).isEqualTo("2026-06-01 09:30:00");
        assertThat(result.get(1).getOrderType()).isEqualTo("SELL");
        assertThat(result.get(1).getRemainQuantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("getPendingOrders - 미체결 조회구분(CCLD_DVSN=02)으로 KIS 를 호출한다")
    void getPendingOrders_UsesUnfilledQueryDivision() {
        // Given: getTradeHistory 와 같은 TR 을 재사용하되 조회구분만 미체결로 바꾼다.
        stubUserWithKisAccount();
        stubKisAuth();
        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of());
        stubCcldGet(body);

        // When
        tradingService.getPendingOrders(userId);

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).get(anyString(), eq("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"),
                eq("TTTC0081R"), anyString(), anyString(), anyString(),
                captor.capture(), eq(KisDailyCcldResponse.class));
        assertThat(captor.getValue()).containsEntry("CCLD_DVSN", "02");
    }

    @Test
    @DisplayName("getPendingOrders - rt_cd!=0 이면 빈 목록으로 graceful 처리한다")
    void getPendingOrders_RtCdNotZero_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("1");
        body.setMsg1("조회 실패");
        body.setOutput1(List.of(ccldItem("O1", "02", "10", "0")));
        stubCcldGet(body);

        // When / Then
        assertThat(tradingService.getPendingOrders(userId)).isEmpty();
    }

    @Test
    @DisplayName("getPendingOrders - 응답 바디가 null 이면 빈 목록을 반환한다")
    void getPendingOrders_NullBody_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisDailyCcldResponse.class)))
                .thenReturn(new ResponseEntity<KisDailyCcldResponse>(HttpStatus.OK));

        // When / Then
        assertThat(tradingService.getPendingOrders(userId)).isEmpty();
    }

    @Test
    @DisplayName("getPendingOrders - 사용자/KIS 예외도 빈 목록으로 삼킨다")
    void getPendingOrders_Exception_ReturnsEmpty() {
        // Given: 미체결 목록은 화면 부가 정보라 예외를 전파하지 않는다.
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThat(tradingService.getPendingOrders(userId)).isEmpty();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getPendingOrders - 주문시각이 없으면 자정, 6자리 미만이면 0 패딩해 매핑한다")
    void getPendingOrders_MissingOrShortOrderTime() {
        // Given: KIS 가 ord_tmd 를 비우거나 "93000"(앞자리 0 누락)으로 주는 경우가 있다.
        stubUserWithKisAccount();
        stubKisAuth();
        KisDailyCcldResponse.DailyCcldItem noTime = ccldItem("O1", "02", "10", "0");
        noTime.setOrdTmd(null);
        noTime.setRmnQty("10");
        KisDailyCcldResponse.DailyCcldItem shortTime = ccldItem("O2", "02", "10", "0");
        shortTime.setOrdTmd("93000");
        shortTime.setRmnQty("10");

        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        body.setOutput1(List.of(noTime, shortTime));
        stubCcldGet(body);

        // When
        List<PendingOrderResponse> result = tradingService.getPendingOrders(userId);

        // Then
        assertThat(result.get(0).getOrderedAt()).isEqualTo("2026-06-01 00:00:00");
        assertThat(result.get(1).getOrderedAt()).isEqualTo("2026-06-01 09:30:00");
    }

    @Test
    @DisplayName("getPendingOrders - output1 이 null 이면 빈 목록을 반환한다")
    void getPendingOrders_NullOutput1_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        KisDailyCcldResponse body = new KisDailyCcldResponse();
        body.setRtCd("0");
        stubCcldGet(body);

        // When / Then
        assertThat(tradingService.getPendingOrders(userId)).isEmpty();
    }

    // ================== getOrderable ==================

    @Test
    @DisplayName("getOrderable - max_buy_qty/ord_psbl_cash 를 콤마·소수점까지 안전하게 파싱한다")
    void getOrderable_ParsesCommaAndDecimal() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", Map.of("max_buy_qty", "1,234", "ord_psbl_cash", "5000.75"));
        stubMapGet(body);

        // When
        OrderableResponse result = tradingService.getOrderable(userId, "005930", new BigDecimal("70000"));

        // Then
        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getMaxBuyQuantity()).isEqualTo(1234L);
        assertThat(result.getOrderableCash()).isEqualTo(5000L);
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getOrderable - 숫자로 파싱할 수 없거나 비어 있는 값은 0 으로 보정한다")
    void getOrderable_UnparsableValues_FallBackToZero() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> output = new HashMap<>();
        output.put("max_buy_qty", "N/A");
        output.put("ord_psbl_cash", "   ");
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", output);
        stubMapGet(body);

        // When
        OrderableResponse result = tradingService.getOrderable(userId, "005930", new BigDecimal("70000"));

        // Then: degrade 가 아니라 정상 응답(notice=null)에 값만 0 이다.
        assertThat(result.getMaxBuyQuantity()).isZero();
        assertThat(result.getOrderableCash()).isZero();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getOrderable - 자체 rate limit 거부는 notice degrade 가 아니라 그대로 전파한다(4007)")
    void getOrderable_RateLimited_Propagates() {
        // Given: 토큰 버킷이 KIS 로 요청을 보내기 전에 거부한다.
        // 이것까지 notice 로 삼키면 verifyBuyingPower 가 fail-open 으로 검증을 건너뛴다.
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException("KIS 호출 한도를 초과해 요청을 보내지 않았습니다"));

        // When / Then
        assertThatThrownBy(() -> tradingService.getOrderable(userId, "005930", new BigDecimal("70000")))
                .isInstanceOf(KisRateLimitExceededException.class)
                .extracting(e -> ((KisRateLimitExceededException) e).getErrorCode())
                .isEqualTo(ErrorCode.KIS_API_RATE_LIMITED);
    }

    @Test
    @DisplayName("executeBuy - 매수여력 조회가 rate limit 이면 주문을 내지 않는다(검증 스킵 금지)")
    void executeBuy_OrderableRateLimited_DoesNotPlaceOrder() {
        // Given: 지정가 매수 → verifyBuyingPower 가 getOrderable 을 부르는데 그 GET 이 rate limit 에 걸린다.
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException("KIS 호출 한도를 초과해 요청을 보내지 않았습니다"));

        // When / Then: fail-open 으로 통과시키면 검증 없이 주문(POST)이 나간다.
        assertThatThrownBy(() -> tradingService.executeBuy(
                userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000")))
                .isInstanceOf(KisRateLimitExceededException.class);

        verify(kisApiClient, never()).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getOrderable - 지정가는 ORD_UNPR 에 주문단가를, 미지정이면 0 을 보낸다")
    void getOrderable_SendsOrderUnitPrice() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", Map.of("max_buy_qty", "10", "ord_psbl_cash", "1000"));
        stubMapGet(body);

        // When
        tradingService.getOrderable(userId, "005930", new BigDecimal("70000.00"));
        tradingService.getOrderable(userId, "005930", null);
        tradingService.getOrderable(userId, "005930", BigDecimal.ZERO);

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient, times(3)).get(anyString(), eq("/uapi/domestic-stock/v1/trading/inquire-psbl-order"),
                eq("TTTC8908R"), anyString(), anyString(), anyString(), captor.capture(), eq(Map.class));
        assertThat(captor.getAllValues().get(0)).containsEntry("ORD_UNPR", "70000.00");
        assertThat(captor.getAllValues().get(1)).containsEntry("ORD_UNPR", "0");
        assertThat(captor.getAllValues().get(2)).containsEntry("ORD_UNPR", "0");
        assertThat(captor.getAllValues().get(0)).containsEntry("ORD_DVSN", "00");
    }

    @Test
    @DisplayName("getOrderable - rt_cd!=0 이면 0 + notice 로 degrade 한다")
    void getOrderable_RtCdNotZero_Degrades() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "조회 권한이 없습니다");
        stubMapGet(body);

        // When
        OrderableResponse result = tradingService.getOrderable(userId, "005930", new BigDecimal("70000"));

        // Then
        assertThat(result.getMaxBuyQuantity()).isZero();
        assertThat(result.getOrderableCash()).isZero();
        assertThat(result.getNotice()).isEqualTo("주문가능 정보를 불러오지 못했습니다");
    }

    @Test
    @DisplayName("getOrderable - 응답 바디가 null 이면 degrade 한다")
    void getOrderable_NullBody_Degrades() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(HttpStatus.OK));

        // When / Then
        assertThat(tradingService.getOrderable(userId, "005930", new BigDecimal("70000")).getNotice())
                .isEqualTo("주문가능 정보를 불러오지 못했습니다");
    }

    @Test
    @DisplayName("getOrderable - output 이 Map 이 아니면 degrade 한다")
    void getOrderable_OutputNotMap_Degrades() {
        // Given: KIS 가 output 을 배열/문자열로 주는 변형 응답을 방어한다.
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", List.of("unexpected"));
        stubMapGet(body);

        // When / Then
        assertThat(tradingService.getOrderable(userId, "005930", new BigDecimal("70000")).getNotice())
                .isEqualTo("주문가능 정보를 불러오지 못했습니다");
    }

    @Test
    @DisplayName("getOrderable - 계좌 해석 실패 같은 예외도 degrade 로 흡수한다(절대 전파하지 않음)")
    void getOrderable_Exception_Degrades() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When
        OrderableResponse result = tradingService.getOrderable(userId, "005930", new BigDecimal("70000"));

        // Then
        assertThat(result.getNotice()).isEqualTo("주문가능 정보를 불러오지 못했습니다");
        verifyNoInteractions(kisApiClient);
    }

    // ================== placeReservedOrder ==================

    private PlaceReservedOrderRequest reservedRequest(String side, String priceType, Long price) {
        return new PlaceReservedOrderRequest("005930", 10, price, side, priceType, "20260701");
    }

    @Test
    @DisplayName("placeReservedOrder - 매수/지정가는 SLL_BUY_DVSN_CD=02, ORD_DVSN_CD=00, ORD_UNPR=주문가로 보낸다")
    void placeReservedOrder_BuyLimit_SendsKisCodes() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("msg1", "예약주문 접수 완료");
        body.put("output", Map.of("RSVN_ORD_SEQ", "SEQ001", "RSVN_ORD_ORGNO", "ORG001"));
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("예약주문 접수 완료");
        assertThat(result.getReservationSeq()).isEqualTo("SEQ001");
        assertThat(result.getOrgNo()).isEqualTo("ORG001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).post(anyString(), eq("/uapi/domestic-stock/v1/trading/order-resv"),
                eq("CTSC0008U"), anyString(), anyString(), anyString(), captor.capture(), eq(Map.class));
        assertThat(captor.getValue())
                .containsEntry("SLL_BUY_DVSN_CD", "02")
                .containsEntry("ORD_DVSN_CD", "00")
                .containsEntry("ORD_UNPR", "70000")
                .containsEntry("ORD_QTY", "10")
                .containsEntry("PDNO", "005930")
                .containsEntry("RSVN_ORD_END_DT", "20260701");
    }

    @Test
    @DisplayName("placeReservedOrder - 매도/시장가는 SLL_BUY_DVSN_CD=01, ORD_DVSN_CD=01, ORD_UNPR=0 으로 보낸다")
    void placeReservedOrder_SellMarket_SendsKisCodes() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("SELL", "MARKET", 70000L));

        // Then: msg1/output 이 없으면 기본 메시지 + null seq
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("예약주문이 접수되었습니다.");
        assertThat(result.getReservationSeq()).isNull();
        assertThat(result.getOrgNo()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                captor.capture(), eq(Map.class));
        assertThat(captor.getValue())
                .containsEntry("SLL_BUY_DVSN_CD", "01")
                .containsEntry("ORD_DVSN_CD", "01")
                .containsEntry("ORD_UNPR", "0");
    }

    @Test
    @DisplayName("placeReservedOrder - 지정가인데 price 가 null 이면 0 으로 보낸다")
    void placeReservedOrder_NullPrice_SendsZero() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        stubMapPost(body);

        // When
        tradingService.placeReservedOrder(userId, kisAccountId, reservedRequest("buy", "limit", null));

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                captor.capture(), eq(Map.class));
        assertThat(captor.getValue()).containsEntry("ORD_UNPR", "0");
    }

    @Test
    @DisplayName("placeReservedOrder - rt_cd!=0 이면 예외 대신 success=false + KIS 메시지를 반환한다")
    void placeReservedOrder_RtCdNotZero_ReturnsFailure() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "예약주문 가능시간이 아닙니다.");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("예약주문 가능시간이 아닙니다.");
    }

    @Test
    @DisplayName("placeReservedOrder - rt_cd!=0 이고 msg1 이 없으면 기본 실패 메시지를 반환한다")
    void placeReservedOrder_RtCdNotZeroWithoutMessage_ReturnsDefaultFailure() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("예약주문 접수에 실패했습니다.");
    }

    @Test
    @DisplayName("placeReservedOrder - 응답 바디가 null 이면 success=false 로 반환한다")
    void placeReservedOrder_NullBody_ReturnsFailure() {
        // Given
        stubKisAuth();
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(HttpStatus.OK));

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("KIS 예약주문 응답이 비어 있습니다.");
    }

    @Test
    @DisplayName("placeReservedOrder - 예외가 나도 전파하지 않고 success=false 로 감싼다")
    void placeReservedOrder_Exception_ReturnsFailure() {
        // Given
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenThrow(new RuntimeException("token expired"));

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("예약주문 접수 중 오류가 발생했습니다").contains("token expired");
    }

    @Test
    @DisplayName("placeReservedOrder - output 이 Map 이 아니면 seq/orgNo 없이 성공 처리한다")
    void placeReservedOrder_OutputNotMap_ReturnsNullSeq() {
        // Given: 실전 응답 필드명이 확정되지 않아 방어적으로 매핑한다.
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", "not-a-map");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getReservationSeq()).isNull();
    }

    @Test
    @DisplayName("placeReservedOrder - 대체 키(odno/ord_gno_brno)로도 seq/orgNo 를 추출한다")
    void placeReservedOrder_FallbackOutputKeys() {
        // Given
        stubKisAuth();
        Map<String, Object> output = new HashMap<>();
        output.put("RSVN_ORD_SEQ", "   ");   // 공백은 없는 값으로 취급되어 다음 후보로 넘어가야 한다
        output.put("odno", "ODNO777");
        output.put("ord_gno_brno", "BR001");
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", output);
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.placeReservedOrder(
                userId, kisAccountId, reservedRequest("buy", "limit", 70000L));

        // Then
        assertThat(result.getReservationSeq()).isEqualTo("ODNO777");
        assertThat(result.getOrgNo()).isEqualTo("BR001");
    }

    // ================== getReservedOrders ==================

    @Test
    @DisplayName("getReservedOrders - output 행을 side/priceType 규칙에 맞춰 매핑한다")
    void getReservedOrders_MapsRows() {
        // Given: 01→sell, 02→buy, 그 외→null / ord_dvsn_cd 01→market, 그 외→limit
        stubUserWithKisAccount();
        stubKisAuth();

        Map<String, Object> sellRow = new HashMap<>();
        sellRow.put("rsvn_ord_seq", "S1");
        sellRow.put("rsvn_ord_orgno", "ORG1");
        sellRow.put("rsvn_ord_ord_dt", "20260701");
        sellRow.put("pdno", "005930");
        sellRow.put("prdt_name", "삼성전자");
        sellRow.put("sll_buy_dvsn_cd", "01");
        sellRow.put("ord_dvsn_cd", "01");
        sellRow.put("ord_qty", "10");
        sellRow.put("ord_unpr", "0");
        sellRow.put("prcs_dvsn_cd", "접수");
        sellRow.put("rsvn_ord_end_dt", "20260731");

        Map<String, Object> buyRow = new HashMap<>();
        buyRow.put("RSVN_ORD_SEQ", "S2");
        buyRow.put("SLL_BUY_DVSN_CD", "02");
        buyRow.put("ORD_DVSN_CD", "00");
        buyRow.put("ORD_QTY", "5");
        buyRow.put("ORD_UNPR", "70000");

        Map<String, Object> unknownSideRow = new HashMap<>();
        unknownSideRow.put("rsvn_ord_seq", "S3");
        unknownSideRow.put("sll_buy_dvsn_cd", "99");

        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output", List.of(sellRow, buyRow, unknownSideRow, "not-a-map"));
        stubMapGet(body);

        // When
        List<ReservedOrderResponse> result = tradingService.getReservedOrders(userId);

        // Then: Map 이 아닌 행은 건너뛴다.
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getSeq()).isEqualTo("S1");
        assertThat(result.get(0).getOrgNo()).isEqualTo("ORG1");
        assertThat(result.get(0).getOrderDate()).isEqualTo("20260701");
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
        assertThat(result.get(0).getSide()).isEqualTo("sell");
        assertThat(result.get(0).getPriceType()).isEqualTo("market");
        assertThat(result.get(0).getQuantity()).isEqualTo(10L);
        assertThat(result.get(0).getStatus()).isEqualTo("접수");
        assertThat(result.get(0).getEndDate()).isEqualTo("20260731");
        assertThat(result.get(1).getSide()).isEqualTo("buy");
        assertThat(result.get(1).getPriceType()).isEqualTo("limit");
        assertThat(result.get(1).getPrice()).isEqualTo(70000L);
        assertThat(result.get(2).getSide()).isNull();
    }

    @Test
    @DisplayName("getReservedOrders - output 이 없으면 output1 로 대체 조회한다")
    void getReservedOrders_FallsBackToOutput1() {
        // Given: KIS 리스트 TR 은 output 또는 output1 을 쓸 수 있다.
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> row = new HashMap<>();
        row.put("rsvn_ord_seq", "S9");
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("output1", List.of(row));
        stubMapGet(body);

        // When
        List<ReservedOrderResponse> result = tradingService.getReservedOrders(userId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeq()).isEqualTo("S9");
    }

    @Test
    @DisplayName("getReservedOrders - output/output1 이 모두 리스트가 아니면 빈 목록을 반환한다")
    void getReservedOrders_NoListOutput_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        stubMapGet(body);

        // When / Then
        assertThat(tradingService.getReservedOrders(userId)).isEmpty();
    }

    @Test
    @DisplayName("getReservedOrders - rt_cd!=0 또는 바디 null 이면 빈 목록을 반환한다")
    void getReservedOrders_RtCdNotZero_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "실전 전용 TR");
        stubMapGet(body);

        // When / Then
        assertThat(tradingService.getReservedOrders(userId)).isEmpty();
    }

    @Test
    @DisplayName("getReservedOrders - 응답 바디가 null 이면 빈 목록을 반환한다")
    void getReservedOrders_NullBody_ReturnsEmpty() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(HttpStatus.OK));

        // When / Then
        assertThat(tradingService.getReservedOrders(userId)).isEmpty();
    }

    @Test
    @DisplayName("getReservedOrders - 사용자를 찾지 못해도 예외 대신 빈 목록을 반환한다")
    void getReservedOrders_UserNotFound_ReturnsEmpty() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThat(tradingService.getReservedOrders(userId)).isEmpty();
        verifyNoInteractions(kisApiClient);
    }

    @Test
    @DisplayName("getReservedOrders - DB 예외를 삼키고 빈 목록을 반환한다")
    void getReservedOrders_Exception_ReturnsEmpty() {
        // Given
        when(userRepository.findById(userId)).thenThrow(new RuntimeException("DB down"));

        // When / Then
        assertThat(tradingService.getReservedOrders(userId)).isEmpty();
        verifyNoInteractions(kisApiClient);
    }

    // ================== cancelReservedOrder ==================

    @Test
    @DisplayName("cancelReservedOrder - 성공 시 seq/orgNo 를 그대로 되돌려준다")
    void cancelReservedOrder_Success() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("msg1", "취소 완료");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, "SEQ1", "ORG1", "20260701");

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("취소 완료");
        assertThat(result.getReservationSeq()).isEqualTo("SEQ1");
        assertThat(result.getOrgNo()).isEqualTo("ORG1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).post(anyString(), eq("/uapi/domestic-stock/v1/trading/order-resv-rvsecncl"),
                eq("CTSC0009U"), anyString(), anyString(), anyString(), captor.capture(), eq(Map.class));
        assertThat(captor.getValue())
                .containsEntry("RSVN_ORD_SEQ", "SEQ1")
                .containsEntry("RSVN_ORD_ORGNO", "ORG1")
                .containsEntry("RSVN_ORD_ORD_DT", "20260701");
    }

    @Test
    @DisplayName("cancelReservedOrder - null 파라미터는 빈 문자열로 전송하고 기본 성공 메시지를 쓴다")
    void cancelReservedOrder_NullParams_SendsEmptyStrings() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, null, null, null);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("예약주문이 취소되었습니다.");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kisApiClient).post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                captor.capture(), eq(Map.class));
        assertThat(captor.getValue())
                .containsEntry("RSVN_ORD_SEQ", "")
                .containsEntry("RSVN_ORD_ORGNO", "")
                .containsEntry("RSVN_ORD_ORD_DT", "");
    }

    @Test
    @DisplayName("cancelReservedOrder - rt_cd!=0 이면 success=false + KIS 메시지를 반환한다")
    void cancelReservedOrder_RtCdNotZero_ReturnsFailure() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "1");
        body.put("msg1", "이미 취소된 주문입니다.");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, "SEQ1", "ORG1", "20260701");

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("이미 취소된 주문입니다.");
        assertThat(result.getReservationSeq()).isEqualTo("SEQ1");
    }

    @Test
    @DisplayName("cancelReservedOrder - rt_cd!=0 이고 msg1 이 없으면 기본 실패 메시지를 반환한다")
    void cancelReservedOrder_RtCdNotZeroWithoutMessage_ReturnsDefaultFailure() {
        // Given
        stubKisAuth();
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "9");
        stubMapPost(body);

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, "SEQ1", "ORG1", "20260701");

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("예약주문 취소에 실패했습니다.");
    }

    @Test
    @DisplayName("cancelReservedOrder - 응답 바디가 null 이면 success=false 로 반환한다")
    void cancelReservedOrder_NullBody_ReturnsFailure() {
        // Given
        stubKisAuth();
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(HttpStatus.OK));

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, "SEQ1", "ORG1", "20260701");

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("KIS 예약주문 취소 응답이 비어 있습니다.");
        assertThat(result.getOrgNo()).isEqualTo("ORG1");
    }

    @Test
    @DisplayName("cancelReservedOrder - 예외가 나도 전파하지 않고 success=false 로 감싼다")
    void cancelReservedOrder_Exception_ReturnsFailure() {
        // Given
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenThrow(new RuntimeException("network down"));

        // When
        ReservedOrderResultResponse result = tradingService.cancelReservedOrder(
                userId, kisAccountId, "SEQ1", "ORG1", "20260701");

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("예약주문 취소 중 오류가 발생했습니다").contains("network down");
        assertThat(result.getReservationSeq()).isEqualTo("SEQ1");
    }

    // ================== getHoldings ==================

    private KisBalanceResponse.Output1 holdingItem(String pdno, String name, String qty,
                                                   String pchsAmt, String evluAmt, String pfls) {
        KisBalanceResponse.Output1 o = new KisBalanceResponse.Output1();
        o.setPdno(pdno);
        o.setPrdtName(name);
        o.setHldgQty(qty);
        o.setOrdPsblQty(qty);
        o.setPchsAvgPric("70000");
        o.setPrpr("71000");
        o.setEvluAmt(evluAmt);
        o.setEvluPflsAmt(pfls);
        o.setEvluPflsRt("1.43");
        o.setPchsAmt(pchsAmt);
        return o;
    }

    private void stubBalanceGet(KisBalanceResponse body) {
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisBalanceResponse.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    @Test
    @DisplayName("getHoldings - output2 요약값이 있으면 그대로 사용한다")
    void getHoldings_UsesOutput2Summary() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisBalanceResponse.Output2 summary = new KisBalanceResponse.Output2();
        summary.setTotEvluAmt("1010000");
        summary.setPchsAmtSmtl("1000000");
        summary.setEvluPflsSmtl("10000");
        summary.setEvluPflsRt("1.00");
        summary.setDncaTotAmt("500000");

        KisBalanceResponse body = new KisBalanceResponse();
        body.setOutput1(List.of(holdingItem("005930", "삼성전자", "10", "700000", "710000", "10000")));
        body.setOutput2(List.of(summary));
        stubBalanceGet(body);

        // When
        BalanceSummaryResponse result = tradingService.getHoldings(userId);

        // Then
        assertThat(result.getHoldings()).hasSize(1);
        assertThat(result.getHoldings().get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.getHoldings().get(0).getStockName()).isEqualTo("삼성전자");
        assertThat(result.getHoldings().get(0).getHoldingQuantity()).isEqualTo(10);
        assertThat(result.getHoldings().get(0).getAvailableQuantity()).isEqualTo(10);
        assertThat(result.getHoldings().get(0).getAveragePrice()).isEqualByComparingTo("70000");
        assertThat(result.getHoldings().get(0).getCurrentPrice()).isEqualByComparingTo("71000");
        assertThat(result.getTotalEvaluationAmount()).isEqualByComparingTo("1010000");
        assertThat(result.getTotalPurchaseAmount()).isEqualByComparingTo("1000000");
        assertThat(result.getTotalProfitLoss()).isEqualByComparingTo("10000");
        assertThat(result.getTotalProfitLossRate()).isEqualByComparingTo("1.00");
        assertThat(result.getCashBalance()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("getHoldings - output2 요약이 비면 보유종목 합계로 총액·수익률을 계산한다")
    void getHoldings_NoSummary_CalculatesFromHoldings() {
        // Given: KIS 가 output2 를 비워 보내는 경우가 있다.
        stubUserWithKisAccount();
        stubKisAuth();

        KisBalanceResponse body = new KisBalanceResponse();
        body.setOutput1(List.of(
                holdingItem("005930", "삼성전자", "10", "700000", "710000", "10000"),
                holdingItem("000660", "SK하이닉스", "5", "300000", "330000", "30000")));
        body.setOutput2(List.of());
        stubBalanceGet(body);

        // When
        BalanceSummaryResponse result = tradingService.getHoldings(userId);

        // Then: 40000 / 1000000 * 100 = 4.0000
        assertThat(result.getTotalPurchaseAmount()).isEqualByComparingTo("1000000");
        assertThat(result.getTotalEvaluationAmount()).isEqualByComparingTo("1040000");
        assertThat(result.getTotalProfitLoss()).isEqualByComparingTo("40000");
        assertThat(result.getTotalProfitLossRate()).isEqualByComparingTo("4.0000");
    }

    @Test
    @DisplayName("getHoldings - 매입금액 합계가 0 이면 수익률을 0 으로 둔다(0 나눗셈 방지)")
    void getHoldings_ZeroPurchaseAmount_RateIsZero() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisBalanceResponse body = new KisBalanceResponse();
        body.setOutput1(List.of(holdingItem("005930", "삼성전자", "10", "0", "0", "0")));
        stubBalanceGet(body);

        // When
        BalanceSummaryResponse result = tradingService.getHoldings(userId);

        // Then
        assertThat(result.getTotalPurchaseAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalProfitLossRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getHoldings - 보유종목이 없으면 요약 계산을 건너뛰고 0 요약을 반환한다")
    void getHoldings_EmptyHoldings_ReturnsZeroSummary() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();

        KisBalanceResponse body = new KisBalanceResponse();
        body.setOutput1(List.of());
        body.setOutput2(List.of());
        stubBalanceGet(body);

        // When
        BalanceSummaryResponse result = tradingService.getHoldings(userId);

        // Then
        assertThat(result.getHoldings()).isEmpty();
        assertThat(result.getTotalEvaluationAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getCashBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getHoldings - KIS 응답 바디가 null 이면 0 으로 채운 요약을 반환한다")
    void getHoldings_NullBody_ReturnsZeroSummary() {
        // Given
        stubUserWithKisAccount();
        stubKisAuth();
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), eq(KisBalanceResponse.class)))
                .thenReturn(new ResponseEntity<KisBalanceResponse>(HttpStatus.OK));

        // When
        BalanceSummaryResponse result = tradingService.getHoldings(userId);

        // Then
        assertThat(result.getHoldings()).isEmpty();
        assertThat(result.getTotalEvaluationAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalPurchaseAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalProfitLoss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalProfitLossRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getCashBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getHoldings - 사용자를 찾지 못하면 UserNotFoundException 을 던진다")
    void getHoldings_UserNotFound_Throws() {
        // Given: 잔고는 핵심 데이터라 graceful degrade 하지 않고 예외를 전파한다.
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> tradingService.getHoldings(userId))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(kisApiClient);
    }
}
