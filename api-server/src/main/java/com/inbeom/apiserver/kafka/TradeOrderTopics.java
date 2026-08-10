package com.inbeom.apiserver.kafka;

/**
 * 매매 주문 파이프라인 토픽 이름. ai-agent 와 공유하는 고정 계약이므로 임의로 바꾸지 않는다.
 */
public final class TradeOrderTopics {

    /** ai-agent 발행 → api-server 소비. key = idempotencyKey. */
    public static final String REQUESTED = "trade.order.requested";

    /** api-server 발행 → ai-agent 소비. */
    public static final String RESULT = "trade.order.result";

    /** api-server 발행 → 사람이 KIS 체결내역과 대조. */
    public static final String DLQ = "trade.order.dlq";

    private TradeOrderTopics() {
    }
}
