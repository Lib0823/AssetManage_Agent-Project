package com.inbeom.apiserver.dto.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 실시간 체결가(틱) 메시지 (브라우저로 fan-out).
 *
 * <p>국내 H0STCNT0 / 미국 HDFSCNT0 상향 프레임을 파싱해 브라우저로 보내는 push 메시지.
 *
 * <p><b>와이어 키는 camelCase 다</b> — REST {@code StockPriceResponse} 와 같은
 * {@code currentPrice / changeAmount / changeRate} 를 쓴다. 프론트가 REST 응답과 틱을 같은
 * 렌더 경로로 흘려보내기 때문에, 여기서 snake_case 를 쓰면 같은 값이 키만 달라 두 번 매핑된다.
 *
 * <p>가격은 국내(원) 정수, 미국(USD) 소수라 {@code currentPrice} 를 {@link BigDecimal} 로 둔다.
 *
 * <p>JSON 형태(브라우저 수신):
 * <pre>{"type":"tick","market":"KR","symbol":"005930",
 *      "currentPrice":70100,"changeAmount":100,"changeRate":0.14,
 *      "volume":12,"accVolume":1234567,"ts":1699999999999}</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TickMessage {

    /** 메시지 타입 식별자 (프론트 라우팅용). */
    @JsonProperty("type")
    @Builder.Default
    private String type = "tick";

    /** 시장 구분 ("KR" | "US"). */
    @JsonProperty("market")
    private String market;

    /** 종목 코드 (국내 6자리 / 미국 티커). */
    @JsonProperty("symbol")
    private String symbol;

    /** 현재가(체결가). 국내 원(정수), 미국 USD(소수). REST StockPriceResponse.currentPrice 와 동일 키. */
    @JsonProperty("currentPrice")
    private BigDecimal currentPrice;

    /** 전일 대비 (부호 포함). REST StockPriceResponse.changeAmount 와 동일 키. */
    @JsonProperty("changeAmount")
    private BigDecimal changeAmount;

    /** 전일 대비 등락률 (%). REST StockPriceResponse.changeRate 와 동일 키. */
    @JsonProperty("changeRate")
    private BigDecimal changeRate;

    /** 체결 수량 (이번 틱). */
    @JsonProperty("volume")
    private Long volume;

    /** 누적 거래량. */
    @JsonProperty("accVolume")
    private Long accVolume;

    /** 서버 수신 시각 (epoch millis). */
    @JsonProperty("ts")
    private long ts;
}
