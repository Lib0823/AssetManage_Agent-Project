package com.inbeom.apiserver.dto.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 실시간 체결통보(fill notification) 메시지 (브라우저로 fan-out).
 *
 * <p>국내 H0STCNI0(실전)/H0STCNI9(모의) 체결통보 암호 프레임을 AES 복호 → 파싱해 만든
 * 계좌 단위 push 메시지. 종목이 아닌 사용자 계좌 단위 이벤트이므로 프론트는 전역(App) 토스트로
 * 처리한다(뷰별 구독 아님). {@link TickMessage} 와 동일한 직렬화 스타일(@JsonInclude NON_NULL)을 따른다.
 *
 * <p>JSON 형태(브라우저 수신):
 * <pre>{"type":"fill","market":"KR","symbol":"005930","side":"buy",
 *      "orderNo":"0000123456","qty":10,"price":70100,
 *      "filledAt":"093015","isFill":true,"ts":1699999999999}</pre>
 *
 * <p>{@code isFill} 은 체결 여부(CNTG_YN=="2") — 주문접수/정정/취소 통보(CNTG_YN=="1")와 실체결을
 * 구별한다. 프론트는 {@code isFill==true} 인 메시지만 "체결" 토스트로 표시한다.
 *
 * <p><b>{@code isFill} 의 {@code @JsonProperty} 는 지우면 안 된다</b> — Lombok 게터가
 * {@code isFill()} 이라 Jackson 이 boolean 프리픽스를 벗겨 와이어 키를 {@code "fill"} 로 추론한다.
 * 값을 명시해야 키가 고정된다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FillMessage {

    /** 메시지 타입 식별자 (프론트 라우팅용). */
    @JsonProperty("type")
    @Builder.Default
    private String type = "fill";

    /** 시장 구분 (KR 전용 MVP). */
    @JsonProperty("market")
    @Builder.Default
    private String market = "KR";

    /** 종목 코드 (국내 6자리). */
    @JsonProperty("symbol")
    private String symbol;

    /** 매매 구분 ("buy" | "sell"). SELN_BYOV_CLS 로 결정. */
    @JsonProperty("side")
    private String side;

    /** 주문번호 (KIS ODER_NO). */
    @JsonProperty("orderNo")
    private String orderNo;

    /** 체결 수량 (CNTG_QTY). 접수/정정 통보에는 없을 수 있음 → nullable. */
    @JsonProperty("qty")
    private Long qty;

    /** 체결 단가 (CNTG_UNPR). 국내 원(정수). */
    @JsonProperty("price")
    private BigDecimal price;

    /** 체결 시각 (STCK_CNTG_HOUR, HHMMSS 문자열). */
    @JsonProperty("filledAt")
    private String filledAt;

    /**
     * 실체결 여부 (CNTG_YN=="2"). 접수/정정/취소 통보는 false.
     *
     * <p>필드명이 {@code fill} 인 것은 의도적이다. Lombok 게터가 {@code isFill()} 이라 Jackson 이
     * 이 게터를 암묵 이름 {@code fill} 로 읽는데, 필드명을 {@code isFill} 로 두면 필드와 게터가
     * <b>서로 다른 프로퍼티</b>로 인식돼 {@code isFill} 과 {@code fill} 두 키가 모두 나간다.
     * 필드명을 {@code fill} 로 맞춰 하나로 합친 뒤 {@code @JsonProperty} 로 와이어 키를 고정한다.
     */
    @JsonProperty("isFill")
    private boolean fill;

    /** 서버 수신 시각 (epoch millis). */
    @JsonProperty("ts")
    private long ts;
}
