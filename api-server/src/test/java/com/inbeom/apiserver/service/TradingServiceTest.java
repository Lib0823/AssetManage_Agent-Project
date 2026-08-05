package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.TradeHistory;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.kis.KisDailyCcldResponse;
import com.inbeom.apiserver.dto.trade.TradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                "https://openapivts.koreainvestment.com:29443"
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
        // Given: 거래내역은 DB에 저장하지 않고 KIS(VTTC0802U) 주문 응답을 그대로 반환한다.
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
                eq("VTTC0802U"),
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
                anyString(), anyString(), eq("VTTC0802U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
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
                eq("VTTC0801U"),
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
                anyString(), anyString(), eq("VTTC0801U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getTradeHistory - 사용자 거래 내역 조회 성공 (KIS inquire-daily-ccld 직접 조회)")
    void getTradeHistory_Success() {
        // Given: 거래내역은 DB가 아니라 KIS API(VTTC0081R)를 직접 조회해 TradeHistoryResponse 로 매핑한다.
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
                anyString(), anyString(), eq("VTTC0802U"), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
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
}
