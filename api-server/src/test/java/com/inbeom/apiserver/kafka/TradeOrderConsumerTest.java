package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import com.inbeom.apiserver.service.TradeOrderIdempotencyService;
import com.inbeom.apiserver.service.TradeOrderIdempotencyService.ClaimResult;
import com.inbeom.apiserver.service.TradingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rate limit 거부 경로의 회귀 테스트.
 *
 * <p>재현하는 버그: 선점 반납({@code release})이 DB 오류로 던지면 원래의
 * {@link KisRateLimitExceededException} 대신 그 예외가 위로 새어 나간다. 그러면 재전달 시
 * {@code claim()} 이 남은 PENDING 을 발견해 "KIS 도달 여부 불확실 — 체결내역 대조 필요"로 DLQ 를
 * 태우는데, 실제로는 KIS 에 소켓조차 열리지 않았으므로 사유가 사실과 다르다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeOrderConsumer — rate limit 거부 시 선점 반납 실패 가드")
class TradeOrderConsumerTest {

    @Mock
    private TradeOrderIdempotencyService idempotencyService;

    @Mock
    private TradingService tradingService;

    @Mock
    private TradeOrderMessagePublisher publisher;

    private TradeOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TradeOrderConsumer(idempotencyService, tradingService, publisher,
                new TradeOrderJsonCodec(new tools.jackson.databind.ObjectMapper()));
    }

    @Test
    @DisplayName("release 가 실패해도 rate limit 예외가 그대로 올라간다 (DLQ 사유가 뒤바뀌지 않는다)")
    void releaseFailureDoesNotMaskRateLimitException() {
        when(idempotencyService.claim(any(TradeOrderRequestMessage.class)))
                .thenReturn(new ClaimResult(false, 42L, null, 7L, "삼성전자"));
        when(tradingService.executeBuy(anyLong(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenThrow(new KisRateLimitExceededException("KIS 호출 한도를 초과해 요청을 보내지 않았습니다"));
        doThrow(new QueryTimeoutException("simulated DB failure"))
                .when(idempotencyService).release(42L);

        assertThatThrownBy(() -> consumer.onTradeOrderRequested(record()))
                .as("반납 실패 예외가 대신 새어 나가면 재전달 경로가 'PENDING 잔여'로 오판한다")
                .isInstanceOf(KisRateLimitExceededException.class);

        // rate limit 은 확정 실패가 아니므로 FAILED 기록도, DLQ 발행도 하지 않는다.
        verify(idempotencyService, never()).markFailed(anyLong());
        verify(publisher, never()).publishDlq(any(TradeOrderRequestMessage.class), anyString(), anyInt());
    }

    private ConsumerRecord<String, String> record() {
        String json = """
                {
                  "idempotencyKey": "1:005930:2026-08-09:BUY",
                  "userId": 1,
                  "stockCode": "005930",
                  "side": "BUY",
                  "quantity": 10,
                  "price": 0,
                  "tradeDate": "2026-08-09",
                  "requestedAt": "2026-08-09T08:55:00+09:00"
                }
                """;
        return new ConsumerRecord<>(TradeOrderTopics.REQUESTED, 0, 0L, "1:005930:2026-08-09:BUY", json);
    }
}
