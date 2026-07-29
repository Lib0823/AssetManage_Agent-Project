package com.inbeom.apiserver.dto.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 실시간 호가 메시지 (브라우저로 fan-out).
 *
 * <p>국내 H0STASP0(10호가) / 미국 HDFSASP0(1호가) 상향 프레임을 파싱해 브라우저로 보내는 push 메시지.
 * 프론트가 REST {@code OrderbookResponse} 와 동일한 렌더 경로를 재사용할 수 있도록
 * {@code asks/bids = [{price, quantity}]} 형태를 그대로 맞춘다(필드 호환).
 *
 * <p>JSON 형태(브라우저 수신):
 * <pre>{"type":"orderbook","market":"KR","symbol":"005930",
 *      "asks":[{"price":70000,"quantity":120}],"bids":[...],"ts":1699999999999}</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuoteMessage {

    /** 메시지 타입 식별자 (프론트 라우팅용). */
    @JsonProperty("type")
    @Builder.Default
    private String type = "orderbook";

    /** 시장 구분 ("KR" | "US"). */
    @JsonProperty("market")
    private String market;

    /** 종목 코드 (국내 6자리 / 미국 티커). */
    @JsonProperty("symbol")
    private String symbol;

    /** 매도호가 (가격 + 잔량). REST OrderbookResponse.asks 와 동형. */
    @JsonProperty("asks")
    private List<Level> asks;

    /** 매수호가 (가격 + 잔량). REST OrderbookResponse.bids 와 동형. */
    @JsonProperty("bids")
    private List<Level> bids;

    /** 서버 수신 시각 (epoch millis). */
    @JsonProperty("ts")
    private long ts;

    /**
     * 호가 단계 (가격 + 잔량). REST {@code OrderbookResponse.OrderbookLevel} 과 필드 호환.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Level {

        @JsonProperty("price")
        private Long price;

        @JsonProperty("quantity")
        private Long quantity;
    }
}
