package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.dto.kafka.TradeOrderDlqMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.dto.kafka.TradeOrderResultMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 매매 주문 메시지 ⇄ JSON 변환.
 *
 * <p>Kafka serde 를 String 으로 고정했기 때문에(=Jackson 2/3 혼재 문제 회피, {@code KafkaConfig}
 * 참고) 직렬화 지점을 여기 한 곳으로 모은다. 애플리케이션이 이미 쓰는 Jackson 3 ObjectMapper 를
 * 그대로 사용하므로 REST 응답과 날짜 포맷이 일치한다(ISO-8601).
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderJsonCodec {

    private final ObjectMapper objectMapper;

    public TradeOrderJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TradeOrderRequestMessage readRequest(String json) {
        return objectMapper.readValue(json, TradeOrderRequestMessage.class);
    }

    public String write(TradeOrderResultMessage message) {
        return objectMapper.writeValueAsString(message);
    }

    public String write(TradeOrderDlqMessage message) {
        return objectMapper.writeValueAsString(message);
    }
}
