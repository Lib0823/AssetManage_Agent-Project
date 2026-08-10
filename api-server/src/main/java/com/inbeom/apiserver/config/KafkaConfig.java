package com.inbeom.apiserver.config;

import com.inbeom.apiserver.kafka.TradeOrderDlqRecoverer;
import com.inbeom.apiserver.kafka.TradeOrderTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 매매 주문 Kafka 설정.
 *
 * <p><b>왜 String serde 인가</b>: Spring Boot 4.1 은 Jackson 3({@code tools.jackson})을 쓰는데
 * spring-kafka 4.1 의 {@code JsonSerializer}/{@code JsonDeserializer} 는 Jackson 2
 * ({@code com.fasterxml.jackson.databind})에 묶여 있어 두 Jackson 이 동시에 클래스패스에 있다.
 * 어느 쪽이 직렬화하느냐에 따라 타입 헤더/날짜 포맷이 달라질 수 있어, 계약이 고정된 메시지에는
 * 위험하다. 그래서 Kafka 레벨에서는 순수 문자열만 주고받고 JSON 매핑은
 * {@code TradeOrderJsonCodec} 이 명시적으로 수행한다 — 와이어 포맷이 문서와 정확히 일치한다.
 *
 * <p><b>재시도 정책</b>: 여기 {@link DefaultErrorHandler} 가 재시도하는 것은 <b>리스너가 예외를
 * 밖으로 던진 경우뿐</b>이다. 컨슈머는 KIS 를 호출한 뒤에는 어떤 예외도 밖으로 던지지 않으므로
 * (직접 FAILED 기록 + DLQ 발행 후 정상 종료), 이 재시도는 KIS 를 아직 건드리지 않은
 * 인프라 오류에만 적용된다. 상세는 {@code TradeOrderConsumer} 참고.
 */
@Slf4j
@EnableKafka
@Configuration
@EnableConfigurationProperties(TradeOrderKafkaProperties.class)
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ================== 토픽 ==================
    // 로컬/도커 개발 편의를 위한 자동 생성. 운영에서 이미 존재하면 무시된다.

    @Bean
    public NewTopic tradeOrderRequestedTopic() {
        return TopicBuilder.name(TradeOrderTopics.REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tradeOrderResultTopic() {
        return TopicBuilder.name(TradeOrderTopics.RESULT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tradeOrderDlqTopic() {
        return TopicBuilder.name(TradeOrderTopics.DLQ).partitions(1).replicas(1).build();
    }

    // ================== 프로듀서 (result / dlq) ==================

    @Bean
    public ProducerFactory<String, String> tradeOrderProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 결과/DLQ 는 유실되면 ai-agent 가 주문 결과를 영영 모르거나 수동 대조 대상이 사라진다.
        // 돈이 걸린 경로이므로 처리량보다 내구성을 택한다.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> tradeOrderKafkaTemplate(
            ProducerFactory<String, String> tradeOrderProducerFactory) {
        return new KafkaTemplate<>(tradeOrderProducerFactory);
    }

    // ================== 컨슈머 (requested) ==================

    @Bean
    public ConsumerFactory<String, String> tradeOrderConsumerFactory(TradeOrderKafkaProperties properties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // earliest: 컨슈머가 잠깐 죽은 사이 발행된 주문을 건너뛰지 않는다(주문 유실 방지가 목적).
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 오프셋 커밋은 컨테이너가 리스너 성공 후에만 수행해야 한다.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 주문 1건 처리에 KIS 왕복이 들어가므로 한 번에 조금씩만 가져온다.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * {@code ExponentialBackOff(초기 2초, 배수 2)} + 최대 3회 재시도 → 소진 시 DLQ.
     * 재시도 대상은 "KIS 를 아직 호출하지 않은" 인프라 오류뿐이다.
     */
    @Bean
    public DefaultErrorHandler tradeOrderErrorHandler(TradeOrderKafkaProperties properties,
                                                      TradeOrderDlqRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                properties.getRetryInitialIntervalMs(), properties.getRetryMultiplier());
        backOff.setMaxAttempts(properties.getRetryMaxAttempts());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // 복구(DLQ 발행)된 레코드의 오프셋을 커밋해 같은 메시지가 무한히 재전달되지 않게 한다.
        errorHandler.setCommitRecovered(true);
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> tradeOrderConsumerFactory,
            DefaultErrorHandler tradeOrderErrorHandler,
            TradeOrderKafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeOrderConsumerFactory);
        factory.setCommonErrorHandler(tradeOrderErrorHandler);
        factory.setConcurrency(properties.getConcurrency());
        // DLQ 메시지에 실제 재시도 횟수를 담기 위해 전달 시도 횟수를 헤더로 노출한다.
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        return factory;
    }
}
