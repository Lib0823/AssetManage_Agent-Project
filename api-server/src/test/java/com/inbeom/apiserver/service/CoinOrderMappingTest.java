package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.UpbitApiClient;
import com.inbeom.apiserver.domain.CoinTradeHistory;
import com.inbeom.apiserver.dto.coin.CoinOrderRequest;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.repository.CoinTradeHistoryRepository;
import com.inbeom.apiserver.service.UpbitAuthService.UpbitCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 업비트 주문 파라미터 매핑 테스트.
 *
 * <p>업비트의 주문 타입은 <b>비대칭</b>이다 — 시장가 매수는 수량이 아니라 <b>총액</b>을,
 * 시장가 매도는 <b>수량</b>을 받는다. 주식의 "시장가 = 플래그 하나" 감각으로 짜면 의도와 다른
 * 주문이 실제로 나가고, 그건 실거래에서 돈이 잘못 움직인다는 뜻이다.
 *
 * <p>세 조합을 각각 고정해 두는 이유가 여기 있다. 하나만 검증하면 나머지 둘이 조용히 깨진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CoinTradingService — 주문 파라미터 매핑")
class CoinOrderMappingTest {

    @Mock private UpbitApiClient upbitApiClient;
    @Mock private UpbitAuthService upbitAuthService;
    @Mock private CoinQuoteService coinQuoteService;
    @Mock private CoinTradeHistoryRepository coinTradeHistoryRepository;

    @InjectMocks private CoinTradingService coinTradingService;

    private static final String MARKET = "KRW-BTC";

    @BeforeEach
    void setUp() {
        given(coinQuoteService.requireKrwMarket(anyString())).willReturn(MARKET);
        given(upbitAuthService.getCredentials(anyLong()))
                .willReturn(new UpbitCredentials("access", "secret"));
        given(coinTradeHistoryRepository.findByIdentifier(anyString())).willReturn(Optional.empty());
        given(coinTradeHistoryRepository.save(any(CoinTradeHistory.class)))
                .willAnswer(inv -> inv.getArgument(0));

        Map<String, Object> ok = new HashMap<>();
        ok.put("uuid", "cdd92199-2897-4e14-9448-f923320408ad");
        ok.put("side", "bid");
        ok.put("ord_type", "limit");
        ok.put("state", "wait");
        ok.put("created_at", "2026-08-29T00:00:00+09:00");
        given(upbitApiClient.postAuthenticated(anyString(), any(), anyString(), anyString(), eq(Map.class)))
                .willReturn(new ResponseEntity<>(ok, HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captureOrderParams() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(upbitApiClient).postAuthenticated(
                eq("/v1/orders"), captor.capture(), anyString(), anyString(), eq(Map.class));
        return captor.getValue();
    }

    private static CoinOrderRequest request(CoinOrderRequest.OrderType type,
                                            BigDecimal quantity, BigDecimal price) {
        CoinOrderRequest req = new CoinOrderRequest();
        req.setMarket(MARKET);
        req.setOrderType(type);
        req.setQuantity(quantity);
        req.setPrice(price);
        return req;
    }

    @Nested
    @DisplayName("지정가")
    class Limit {

        @Test
        @DisplayName("매수는 ord_type=limit 으로 수량과 단가를 함께 보낸다")
        void limitBuySendsVolumeAndPrice() {
            coinTradingService.placeOrder(1L, CoinTradingService.buySide(),
                    request(CoinOrderRequest.OrderType.LIMIT,
                            new BigDecimal("0.001"), new BigDecimal("50000000")));

            Map<String, String> sent = captureOrderParams();
            assertThat(sent.get("side")).isEqualTo("bid");
            assertThat(sent.get("ord_type")).isEqualTo("limit");
            assertThat(sent.get("volume")).isEqualTo("0.001");
            assertThat(sent.get("price")).isEqualTo("50000000");
        }

        @Test
        @DisplayName("매도도 같은 형태다")
        void limitSellSendsVolumeAndPrice() {
            coinTradingService.placeOrder(1L, CoinTradingService.sellSide(),
                    request(CoinOrderRequest.OrderType.LIMIT,
                            new BigDecimal("0.5"), new BigDecimal("49000000")));

            Map<String, String> sent = captureOrderParams();
            assertThat(sent.get("side")).isEqualTo("ask");
            assertThat(sent.get("ord_type")).isEqualTo("limit");
            assertThat(sent.get("volume")).isEqualTo("0.5");
            assertThat(sent.get("price")).isEqualTo("49000000");
        }
    }

    @Nested
    @DisplayName("시장가 — 매수와 매도의 파라미터가 다르다")
    class Market {

        @Test
        @DisplayName("매수는 ord_type=price 로 총액만 보낸다 (volume 미전송)")
        void marketBuySendsTotalAmountOnly() {
            // price 칸에 들어가는 값이 '단가'가 아니라 '주문 총액'이다.
            // volume 을 함께 보내면 업비트가 거부한다.
            coinTradingService.placeOrder(1L, CoinTradingService.buySide(),
                    request(CoinOrderRequest.OrderType.MARKET, null, new BigDecimal("100000")));

            Map<String, String> sent = captureOrderParams();
            assertThat(sent.get("side")).isEqualTo("bid");
            assertThat(sent.get("ord_type")).isEqualTo("price");
            assertThat(sent.get("price")).isEqualTo("100000");
            assertThat(sent).doesNotContainKey("volume");
        }

        @Test
        @DisplayName("매도는 ord_type=market 으로 수량만 보낸다 (price 미전송)")
        void marketSellSendsVolumeOnly() {
            coinTradingService.placeOrder(1L, CoinTradingService.sellSide(),
                    request(CoinOrderRequest.OrderType.MARKET, new BigDecimal("0.001"), null));

            Map<String, String> sent = captureOrderParams();
            assertThat(sent.get("side")).isEqualTo("ask");
            assertThat(sent.get("ord_type")).isEqualTo("market");
            assertThat(sent.get("volume")).isEqualTo("0.001");
            assertThat(sent).doesNotContainKey("price");
        }

        @Test
        @DisplayName("시장가 매수에 총액이 없으면 업비트를 부르기 전에 거부한다")
        void marketBuyWithoutTotalIsRejectedBeforeCallingUpbit() {
            assertThatThrownBy(() -> coinTradingService.placeOrder(1L, CoinTradingService.buySide(),
                    request(CoinOrderRequest.OrderType.MARKET, new BigDecimal("0.001"), null)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("시장가 매도에 수량이 없으면 업비트를 부르기 전에 거부한다")
        void marketSellWithoutQuantityIsRejectedBeforeCallingUpbit() {
            assertThatThrownBy(() -> coinTradingService.placeOrder(1L, CoinTradingService.sellSide(),
                    request(CoinOrderRequest.OrderType.MARKET, null, new BigDecimal("100000"))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("소수 정밀도")
    class Precision {

        @Test
        @DisplayName("사토시 단위 수량이 지수표기로 변질되지 않는다")
        void smallVolumeIsNotSerializedAsScientificNotation() {
            // BigDecimal.toString() 은 0.00000123 을 1.23E-6 으로 만든다.
            // 그대로 업비트에 보내면 주문이 거부되거나 의도와 다른 수량이 나간다.
            coinTradingService.placeOrder(1L, CoinTradingService.sellSide(),
                    request(CoinOrderRequest.OrderType.MARKET, new BigDecimal("0.00000123"), null));

            assertThat(captureOrderParams().get("volume")).isEqualTo("0.00000123");
        }
    }

    @Nested
    @DisplayName("멱등성")
    class Idempotency {

        @Test
        @DisplayName("같은 멱등키의 주문이 이미 있으면 업비트를 다시 부르지 않는다")
        void duplicateIdentifierDoesNotReachUpbit() {
            CoinTradeHistory existing = CoinTradeHistory.builder()
                    .userId(1L).market(MARKET).orderSide("bid").ordType("limit")
                    .submittedState("wait").orderUuid("existing-uuid").identifier("dup-key")
                    .build();
            given(coinTradeHistoryRepository.findByIdentifier("dup-key"))
                    .willReturn(Optional.of(existing));

            CoinOrderRequest req = request(CoinOrderRequest.OrderType.LIMIT,
                    new BigDecimal("0.001"), new BigDecimal("50000000"));
            req.setIdempotencyKey("dup-key");

            coinTradingService.placeOrder(1L, CoinTradingService.buySide(), req);

            // 네트워크 타임아웃 뒤 재시도가 중복 주문이 되는 것을 막는 것이 identifier 의 존재 이유다.
            verify(upbitApiClient, org.mockito.Mockito.never())
                    .postAuthenticated(anyString(), any(), anyString(), anyString(), eq(Map.class));
        }
    }
}
