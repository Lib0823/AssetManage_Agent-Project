package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code trade.order.result} / {@code trade.order.dlq} 발행.
 *
 * <p>발행 실패는 삼키고 ERROR 로그만 남긴다 — 이 시점엔 이미 KIS 주문이 나갔거나 확정 실패로
 * {@code trade_history} 에 기록된 뒤라, 예외를 위로 던져 Kafka 재전달을 유발하면 같은 주문을
 * 다시 처리하려 드는 위험만 생긴다. {@code trade_history} 가 언제나 최종 진실이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TradeOrderJsonCodec codec;

    public TradeOrderMessagePublisher(KafkaTemplate<String, String> tradeOrderKafkaTemplate,
                                      TradeOrderJsonCodec codec) {
        this.kafkaTemplate = tradeOrderKafkaTemplate;
        this.codec = codec;
    }

    /** 주문 결과 발행. key 는 멱등키를 그대로 써서 ai-agent 쪽도 중복을 걸러낼 수 있게 한다. */
    public void publishResult(TradeOrderResultMessage message) {
        try {
            kafkaTemplate.send(TradeOrderTopics.RESULT, message.idempotencyKey(), codec.write(message));
            log.info("Published trade order result: key={}, status={}", message.idempotencyKey(), message.status());
        } catch (Exception e) {
            log.error("Failed to publish trade order result (key={}, status={}). "
                            + "trade_history 가 최종 진실이므로 그쪽을 확인할 것.",
                    message.idempotencyKey(), message.status(), e);
        }
    }

    /** DLQ 발행. */
    public void publishDlq(TradeOrderDlqMessage message) {
        try {
            kafkaTemplate.send(TradeOrderTopics.DLQ, message.idempotencyKey(), codec.write(message));
            log.warn("Published trade order to DLQ: key={}, retryCount={}, reason={}",
                    message.idempotencyKey(), message.retryCount(), message.failureReason());
        } catch (Exception e) {
            log.error("Failed to publish trade order to DLQ (key={}). 수동 대조가 필요하다.",
                    message.idempotencyKey(), e);
        }
    }

    /** 편의 메서드: 요청 메시지 기반 DLQ 발행. */
    public void publishDlq(TradeOrderRequestMessage request, String failureReason, int retryCount) {
        publishDlq(TradeOrderDlqMessage.from(request, failureReason, retryCount));
    }
}
