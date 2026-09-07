package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 업비트 코인 주문 접수 이력.
 *
 * <p>{@link TradeHistory}(주식)를 재사용하지 않는 이유는 형식이 세 곳에서 어긋나기 때문이다:
 * 주문 식별자가 UUID 문자열이고, 수량이 소수 8자리이며, 멱등키 제약이 Kafka 파이프라인 전용이다.
 *
 * <p><b>{@code submittedState} 는 체결 상태가 아니다.</b> {@code POST /v1/orders} 응답의
 * {@code state} 는 접수 직후 값이라 대개 {@code wait} 이고, 주문 조회 API 가 이 기능의 범위 밖이라
 * 이후 갱신되지 않는다. 컬럼명을 정직하게 두어 "체결 안 됨"으로 오인한 사용자가 중복 주문을 내는
 * 상황을 막는다.
 */
@Entity
@Table(name = "coin_trade_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoinTradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 마켓 코드 (예: {@code KRW-BTC}). 6자리 종목코드가 아니다. */
    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "coin_name", length = 50)
    private String coinName;

    /** 업비트 원문: {@code bid}(매수) / {@code ask}(매도). */
    @Column(name = "order_side", nullable = false, length = 10)
    private String orderSide;

    /** 업비트 원문: {@code limit} / {@code price}(시장가 매수) / {@code market}(시장가 매도). */
    @Column(name = "ord_type", nullable = false, length = 10)
    private String ordType;

    @Column(name = "submitted_state", nullable = false, length = 20)
    private String submittedState;

    /** 시장가 매수는 수량을 지정하지 않으므로 null. */
    @Column(name = "volume", precision = 30, scale = 8)
    private BigDecimal volume;

    /** 지정가 단가 또는 시장가 매수 총액. 시장가 매도는 null. */
    @Column(name = "price", precision = 30, scale = 8)
    private BigDecimal price;

    @Column(name = "executed_volume", precision = 30, scale = 8)
    private BigDecimal executedVolume;

    @Column(name = "paid_fee", precision = 30, scale = 8)
    private BigDecimal paidFee;

    @Column(name = "order_uuid", nullable = false, unique = true, length = 64)
    private String orderUuid;

    /** 멱등키. 업비트로는 {@code identifier} 파라미터로 전송된다. */
    @Column(name = "identifier", unique = true, length = 64)
    private String identifier;

    /**
     * 업비트가 보고한 주문 접수 시각.
     *
     * <p>{@code OffsetDateTime} 인 이유: 업비트 {@code created_at} 은 {@code +09:00} 오프셋을 달고
     * 온다. {@code LocalDateTime} 으로 받으면 오프셋이 버려져 9시간 오차가 조용히 생긴다.
     */
    @Column(name = "ordered_at", nullable = false)
    private OffsetDateTime orderedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
