package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code trade.order.result} / {@code trade.order.dlq} 발행.
 *
 * <h2>두 가지 발행 모드</h2>
 * <ul>
 *   <li><b>best-effort</b>({@link #publishResult}, {@link #publishDlq}) — 컨슈머가 KIS 주문을
 *       끝낸 뒤 쓰는 경로다. 발행 실패를 위로 던지면 Kafka 가 재전달해 같은 주문을 다시 처리하려
 *       드는 위험만 생기므로 삼키고 ERROR 로그만 남긴다. {@code trade_history} 가 최종 진실이다.</li>
 *   <li><b>확인 발행</b>({@link #publishDlqOrThrow}) — {@link TradeOrderDlqRecoverer} 전용이다.
 *       거기서 DLQ 메시지는 <b>주문의 마지막 흔적</b>이라, 발행 실패를 삼키면
 *       {@code DefaultErrorHandler.setCommitRecovered(true)} 가 오프셋을 커밋해 원본 주문이
 *       DLQ 에도 없이 사라진다. 그래서 브로커 확인을 기다렸다가 실패 시 예외를 던져 커밋을
 *       막는다(= 재전달되어 다시 시도된다). 같은 경로의 FAILED 결과 발행은 best-effort 로 둔다 —
 *       그쪽이 유실돼도 주문의 기록은 {@code trade_history} 와 DLQ 에 남는다.</li>
 * </ul>
 *
 * <p><b>왜 send() 의 반환 future 를 봐야 하는가</b>: {@code KafkaTemplate.send()} 는 비동기라
 * 브로커 측 실패(acks=all 타임아웃 등)가 예외가 아니라 future 의 실패 완료로 나타난다.
 * 반환값을 버리면 발행 실패가 흐름 제어에 전혀 반영되지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderMessagePublisher {

    /**
     * 확인 발행이 브로커 응답을 기다리는 한계.
     *
     * <p>프로듀서는 {@code acks=all} + 무한 재시도라 실제로는 {@code delivery.timeout.ms}(기본 120초)
     * 까지 매달릴 수 있는데, 그 시간을 그대로 기다리면 컨슈머 스레드가 묶여
     * {@code max.poll.interval.ms}(기본 5분)를 위협한다. 10초에서 끊고 예외를 던지면 오프셋이
     * 커밋되지 않아 재전달로 다시 시도된다 — 최악의 경우 DLQ 메시지가 중복될 수 있지만,
     * 중복은 멱등키로 걸러낼 수 있고 유실은 되돌릴 수 없다.
     */
    private static final long PUBLISH_TIMEOUT_MS = 10_000L;

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
            kafkaTemplate.send(TradeOrderTopics.RESULT, message.idempotencyKey(), codec.write(message))
                    .whenComplete((sendResult, error) -> {
                        if (error != null) {
                            logResultFailure(message, error);
                        } else {
                            log.info("Published trade order result: key={}, status={}",
                                    message.idempotencyKey(), message.status());
                        }
                    });
        } catch (Exception e) {
            logResultFailure(message, e);
        }
    }

    /** DLQ 발행. */
    public void publishDlq(TradeOrderDlqMessage message) {
        try {
            kafkaTemplate.send(TradeOrderTopics.DLQ, message.idempotencyKey(), codec.write(message))
                    .whenComplete((sendResult, error) -> {
                        if (error != null) {
                            logDlqFailure(message, error);
                        } else {
                            log.warn("Published trade order to DLQ: key={}, retryCount={}, reason={}",
                                    message.idempotencyKey(), message.retryCount(), message.failureReason());
                        }
                    });
        } catch (Exception e) {
            logDlqFailure(message, e);
        }
    }

    /** 편의 메서드: 요청 메시지 기반 DLQ 발행. */
    public void publishDlq(TradeOrderRequestMessage request, String failureReason, int retryCount) {
        publishDlq(TradeOrderDlqMessage.from(request, failureReason, retryCount));
    }

    /** 브로커 확인까지 기다리는 DLQ 발행. 실패하면 예외를 던진다. */
    public void publishDlqOrThrow(TradeOrderDlqMessage message) {
        String key = message.idempotencyKey();
        try {
            kafkaTemplate.send(TradeOrderTopics.DLQ, key, codec.write(message))
                    .get(PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("Interrupted while confirming DLQ publish (key=" + key + ")", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaException("Failed to confirm DLQ publish (key=" + key + ")", e);
        }
        log.warn("Published trade order to DLQ (confirmed): key={}, retryCount={}, reason={}",
                key, message.retryCount(), message.failureReason());
    }

    /** 편의 메서드: 요청 메시지 기반 확인 DLQ 발행. */
    public void publishDlqOrThrow(TradeOrderRequestMessage request, String failureReason, int retryCount) {
        publishDlqOrThrow(TradeOrderDlqMessage.from(request, failureReason, retryCount));
    }

    private void logResultFailure(TradeOrderResultMessage message, Throwable error) {
        log.error("Failed to publish trade order result (key={}, status={}). "
                        + "trade_history 가 최종 진실이므로 그쪽을 확인할 것.",
                message.idempotencyKey(), message.status(), error);
    }

    private void logDlqFailure(TradeOrderDlqMessage message, Throwable error) {
        log.error("Failed to publish trade order to DLQ (key={}). 수동 대조가 필요하다.",
                message.idempotencyKey(), error);
    }
}
