package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 재시도 소진 시 호출되는 복구기 — {@code DefaultErrorHandler} 에 연결된다.
 *
 * <p>스프링 기본 {@code DeadLetterPublishingRecoverer} 를 쓰지 않은 이유: 그것은 원본 레코드를
 * 그대로 {@code <topic>.DLT} 로 옮기고 실패 사유를 <b>헤더</b>에 넣는다. 우리 계약은 DLQ 토픽
 * 이름이 {@code trade.order.dlq} 로 고정이고 {@code failureReason}/{@code retryCount} 가
 * <b>본문 필드</b>여야 하므로, 직접 본문을 조립해 발행한다. 덕분에 재시도 소진 경로와 컨슈머의
 * KIS 실패 경로가 <b>동일한 DLQ 본문 형태</b>를 갖는다.
 *
 * <p>여기까지 온 실패는 정의상 "KIS 를 호출하기 전" 단계의 인프라 오류다 — 컨슈머는 KIS 호출
 * 이후에는 예외를 밖으로 던지지 않기 때문이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderDlqRecoverer implements ConsumerRecordRecoverer {

    private final TradeOrderMessagePublisher publisher;
    private final TradeOrderJsonCodec codec;

    public TradeOrderDlqRecoverer(TradeOrderMessagePublisher publisher, TradeOrderJsonCodec codec) {
        this.publisher = publisher;
        this.codec = codec;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String reason = "재시도 소진 (KIS 호출 전 인프라 오류): " + rootReason(exception);
        int retryCount = deliveryAttempt(record);

        TradeOrderRequestMessage request = tryParse(record);
        if (request == null) {
            log.error("Trade order recovery failed and payload is unparseable. offset={}, payload={}",
                    record.offset(), record.value(), exception);
            publisher.publishDlq(TradeOrderDlqMessage.unparseable(
                    reason + " / 원문: " + String.valueOf(record.value())));
            return;
        }

        log.error("Trade order exhausted retries -> DLQ. key={}, retryCount={}",
                request.idempotencyKey(), retryCount, exception);

        // ai-agent 가 결과를 영영 기다리지 않도록 FAILED 결과도 발행한다.
        publisher.publishResult(TradeOrderResultMessage.failed(request, reason));
        publisher.publishDlq(request, reason, retryCount);
    }

    private TradeOrderRequestMessage tryParse(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        if (!(value instanceof String json)) {
            return null;
        }
        try {
            return codec.readRequest(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 실제 전달 시도 횟수. 컨테이너가 {@code deliveryAttemptHeader=true} 로 설정돼 있어야 한다.
     * 최초 시도가 1이므로 재시도 횟수는 그보다 1 적다.
     */
    private int deliveryAttempt(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length < Integer.BYTES) {
            return 0;
        }
        return Math.max(0, ByteBuffer.wrap(header.value()).getInt() - 1);
    }

    private String rootReason(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
