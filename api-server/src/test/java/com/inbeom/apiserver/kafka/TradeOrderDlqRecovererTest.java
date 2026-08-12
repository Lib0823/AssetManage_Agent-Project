package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.KafkaException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 재시도 소진 복구 경로의 회귀 테스트.
 *
 * <p>재현하는 버그: 이 지점의 DLQ 메시지는 주문의 <b>마지막 흔적</b>이라, 발행 실패를 삼키고 정상
 * 반환하면 {@code DefaultErrorHandler.setCommitRecovered(true)} 가 오프셋을 커밋해 원본 주문이
 * DLQ 에도 없이 사라진다. 그래서 {@code accept()} 는 발행 실패 시 예외를 밖으로 내야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeOrderDlqRecoverer — 발행 실패 시 오프셋 커밋 차단")
class TradeOrderDlqRecovererTest {

    @Mock
    private TradeOrderMessagePublisher publisher;

    private TradeOrderDlqRecoverer recoverer;
    private TradeOrderJsonCodec codec;

    @BeforeEach
    void setUp() {
        codec = new TradeOrderJsonCodec(new tools.jackson.databind.ObjectMapper());
        recoverer = new TradeOrderDlqRecoverer(publisher, codec);
    }

    @Test
    @DisplayName("DLQ 발행이 실패하면 예외를 밖으로 던진다 (커밋되면 주문이 흔적 없이 사라진다)")
    void publishFailurePropagates() {
        doThrow(new KafkaException("simulated publish failure"))
                .when(publisher).publishDlqOrThrow(any(TradeOrderRequestMessage.class), anyString(), anyInt());

        assertThatThrownBy(() -> recoverer.accept(record(json()), new QueryTimeoutException("DB down")))
                .isInstanceOf(KafkaException.class);
    }

    @Test
    @DisplayName("역직렬화조차 안 되는 레코드도 DLQ 발행 실패는 그대로 던진다")
    void unparseablePublishFailurePropagates() {
        doThrow(new KafkaException("simulated publish failure"))
                .when(publisher).publishDlqOrThrow(any(TradeOrderDlqMessage.class));

        assertThatThrownBy(() -> recoverer.accept(record("not-json"), new QueryTimeoutException("DB down")))
                .isInstanceOf(KafkaException.class);
    }

    @Test
    @DisplayName("정상 복구 시 FAILED 결과와 DLQ 를 모두 발행하고 예외 없이 끝난다")
    void normalRecoveryPublishesBoth() {
        assertThatCode(() -> recoverer.accept(record(json()), new QueryTimeoutException("DB down")))
                .doesNotThrowAnyException();

        verify(publisher).publishResult(any(TradeOrderResultMessage.class));
        verify(publisher).publishDlqOrThrow(any(TradeOrderRequestMessage.class), anyString(), anyInt());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TradeOrderTopics.REQUESTED, 0, 0L, "1:005930:2026-08-09:BUY", value);
    }

    private String json() {
        return """
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
    }
}
