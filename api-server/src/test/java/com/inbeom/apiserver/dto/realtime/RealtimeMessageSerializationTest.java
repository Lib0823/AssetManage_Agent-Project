package com.inbeom.apiserver.dto.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실시간 push 메시지의 <b>와이어 키</b>를 고정한다.
 *
 * <p>프론트는 이 JSON 을 REST 응답과 같은 렌더 경로로 흘려보내므로 키가 바뀌면 값이 조용히
 * undefined 가 된다(컴파일 에러도 테스트 실패도 나지 않는다). 그래서 필드명이 아니라
 * 직렬화 결과의 키 자체를 검증한다.
 */
@DisplayName("실시간 메시지 직렬화 키 계약 테스트")
class RealtimeMessageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TickMessage - REST StockPriceResponse 와 동일한 camelCase 키로 직렬화된다")
    void tickMessage_SerializesWithCamelCaseKeys() throws Exception {
        TickMessage message = TickMessage.builder()
                .market("KR")
                .symbol("005930")
                .currentPrice(new BigDecimal("70100"))
                .changeAmount(new BigDecimal("100"))
                .changeRate(new BigDecimal("0.14"))
                .volume(12L)
                .accVolume(1234567L)
                .ts(1699999999999L)
                .build();

        String raw = objectMapper.writeValueAsString(message);
        JsonNode json = objectMapper.readTree(raw);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "type", "market", "symbol",
                "currentPrice", "changeAmount", "changeRate",
                "volume", "accVolume", "ts");
        assertThat(raw).doesNotContain("current_price", "change_amount", "change_rate", "acc_volume");
        assertThat(json.get("type").stringValue()).isEqualTo("tick");
        assertThat(json.get("currentPrice").decimalValue()).isEqualByComparingTo("70100");
        assertThat(json.get("accVolume").longValue()).isEqualTo(1234567L);
    }

    @Test
    @DisplayName("FillMessage - camelCase 키로 직렬화되고 isFill 이 'fill' 로 축약되지 않는다")
    void fillMessage_SerializesWithCamelCaseKeys() throws Exception {
        FillMessage message = FillMessage.builder()
                .symbol("005930")
                .side("buy")
                .orderNo("0000123456")
                .qty(10L)
                .price(new BigDecimal("70100"))
                .filledAt("093015")
                .fill(true)
                .ts(1699999999999L)
                .build();

        String raw = objectMapper.writeValueAsString(message);
        JsonNode json = objectMapper.readTree(raw);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "type", "market", "symbol", "side",
                "orderNo", "qty", "price", "filledAt", "isFill", "ts");
        assertThat(raw).doesNotContain("order_no", "filled_at", "is_fill");
        assertThat(json.has("fill")).isFalse();
        assertThat(json.get("isFill").booleanValue()).isTrue();
        assertThat(json.get("orderNo").stringValue()).isEqualTo("0000123456");
        assertThat(json.get("filledAt").stringValue()).isEqualTo("093015");
    }
}
