package com.inbeom.apiserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 매매 주문 Kafka 컨슈머 설정 ({@code kafka.trade-order.*}).
 *
 * <p>재시도 파라미터를 프로퍼티로 뺀 이유: 통합 테스트에서 backoff 를 짧게 줄여야
 * "재시도 소진 후 DLQ" 경로를 현실적인 시간 안에 검증할 수 있기 때문이다.
 * 운영 기본값은 초기 2초 / 배수 2 / 최대 3회 재시도다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kafka.trade-order")
public class TradeOrderKafkaProperties {

    /** 컨슈머 그룹 id. */
    private String groupId = "api-server-trade-order";

    /**
     * 리스너 동시성. 1 로 둔다 — 같은 멱등키는 같은 파티션이라 중복 실행은 어차피 DB UNIQUE 가
     * 막지만, 주문 처리 순서를 단순하게 유지하는 편이 사고 조사에 유리하다.
     */
    private int concurrency = 1;

    /** 첫 재시도까지의 대기(ms). */
    private long retryInitialIntervalMs = 2000L;

    /** 재시도 간격 배수. */
    private double retryMultiplier = 2.0;

    /** 최대 재시도 횟수(최초 시도 제외). 소진되면 DLQ 로 보낸다. */
    private int retryMaxAttempts = 3;
}
