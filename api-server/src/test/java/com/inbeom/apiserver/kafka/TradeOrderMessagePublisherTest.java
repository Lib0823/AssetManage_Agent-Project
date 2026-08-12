package com.inbeom.apiserver.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 발행 실패가 관측되는지에 대한 회귀 테스트.
 *
 * <p>재현하는 버그: {@code KafkaTemplate.send()} 는 비동기라 브로커 측 실패(acks=all 타임아웃 등)가
 * 예외가 아니라 <b>future 의 실패 완료</b>로 나타난다. 반환값을 버리면 실패가 로그에도 흐름 제어에도
 * 전혀 반영되지 않는다. 그래서 여기서는 "실패하는 future" 를 주입해 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeOrderMessagePublisher — 발행 실패 관측")
class TradeOrderMessagePublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private TradeOrderMessagePublisher publisher;
    private ListAppender<ILoggingEvent> logs;
    private Logger publisherLogger;

    @BeforeEach
    void setUp() {
        publisher = new TradeOrderMessagePublisher(kafkaTemplate,
                new TradeOrderJsonCodec(new tools.jackson.databind.ObjectMapper()));

        publisherLogger = (Logger) LoggerFactory.getLogger(TradeOrderMessagePublisher.class);
        logs = new ListAppender<>();
        logs.start();
        publisherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("결과 발행이 브로커에서 실패하면 ERROR 로그로 드러난다 (조용히 유실되지 않는다)")
    void resultPublishFailureIsLogged() {
        when(kafkaTemplate.send(eq(TradeOrderTopics.RESULT), anyString(), anyString()))
                .thenReturn(failedFuture());

        publisher.publishResult(TradeOrderResultMessage.failed(request(), "KIS 타임아웃"));

        assertThat(errorMessages())
                .as("send() 의 실패 future 를 보지 않으면 이 로그가 남지 않는다")
                .anyMatch(msg -> msg.contains("Failed to publish trade order result"));
    }

    @Test
    @DisplayName("best-effort DLQ 발행 실패도 ERROR 로그로 드러난다")
    void dlqPublishFailureIsLogged() {
        when(kafkaTemplate.send(eq(TradeOrderTopics.DLQ), anyString(), anyString()))
                .thenReturn(failedFuture());

        publisher.publishDlq(request(), "계약 위반", 0);

        assertThat(errorMessages())
                .anyMatch(msg -> msg.contains("Failed to publish trade order to DLQ"));
    }

    @Test
    @DisplayName("확인 발행은 브로커 실패 시 예외를 던진다 — 호출부가 오프셋 커밋을 막을 수 있어야 한다")
    void confirmedDlqPublishThrowsOnFailure() {
        when(kafkaTemplate.send(eq(TradeOrderTopics.DLQ), anyString(), anyString()))
                .thenReturn(failedFuture());

        assertThatThrownBy(() -> publisher.publishDlqOrThrow(TradeOrderDlqMessage.from(request(), "재시도 소진", 3)))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining("Failed to confirm DLQ publish");
    }

    @Test
    @DisplayName("확인 발행은 정상 발행 시 예외 없이 끝난다")
    void confirmedDlqPublishSucceeds() {
        when(kafkaTemplate.send(eq(TradeOrderTopics.DLQ), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> publisher.publishDlqOrThrow(TradeOrderDlqMessage.from(request(), "재시도 소진", 3)))
                .doesNotThrowAnyException();
    }

    private CompletableFuture<SendResult<String, String>> failedFuture() {
        return CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("simulated broker timeout"));
    }

    private java.util.List<String> errorMessages() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private TradeOrderRequestMessage request() {
        return new TradeOrderRequestMessage(
                "1:005930:2026-08-09:BUY", 1L, "005930", "BUY", 10,
                BigDecimal.ZERO, LocalDate.of(2026, 8, 9), OffsetDateTime.now());
    }
}
