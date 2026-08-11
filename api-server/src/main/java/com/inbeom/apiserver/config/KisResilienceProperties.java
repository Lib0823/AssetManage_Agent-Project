package com.inbeom.apiserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * KIS 외부 API 의존성 격리 설정 ({@code kis.resilience.*}).
 *
 * <p>두 가지를 다룬다:
 * <ul>
 *   <li><b>rate limit</b> — Redis 토큰 버킷. 모든 KIS 호출의 단일 관문
 *       ({@code KisApiClient.callKisApi})에서 소비한다.</li>
 *   <li><b>cache</b> — 시세/재무 응답 캐시 + stale-if-error.</li>
 * </ul>
 *
 * <p>수치를 프로퍼티로 뺀 이유는 {@code TradeOrderKafkaProperties} 와 같다: 통합 테스트에서
 * 용량/TTL 을 극단값으로 줄여야 "한도 초과 거부", "TTL 만료 후 재호출" 을 현실적인 시간 안에
 * 검증할 수 있다. 더불어 KIS 공식 rate limit 수치가 문서로 확정되지 않아, 운영 중 조정 가능해야 한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kis.resilience")
public class KisResilienceProperties {

    private final RateLimit rateLimit = new RateLimit();
    private final Cache cache = new Cache();

    @Getter
    @Setter
    public static class RateLimit {

        private boolean enabled = true;

        /** 버킷 최대 용량 = 순간 버스트 허용량. */
        private int capacity = 5;

        /** 초당 충전 토큰 수. ai-agent 의 {@code asyncio.Semaphore(5)} 관례와 맞춘 값. */
        private double refillPerSecond = 5.0;
    }

    @Getter
    @Setter
    public static class Cache {

        private boolean enabled = true;

        /** 시세(현재가/호가) 신선도. */
        private Duration quoteTtl = Duration.ofSeconds(30);

        /** 재무(손익/재무비율/안정성비율) 신선도. */
        private Duration financeTtl = Duration.ofHours(24);

        /** 시세 stale-if-error 보관 기간. */
        private Duration quoteStaleGrace = Duration.ofHours(24);

        /** 재무 stale-if-error 보관 기간. */
        private Duration financeStaleGrace = Duration.ofDays(7);

        /** 주식현재가 시세. */
        private static final String TR_PRICE = "FHKST01010100";
        /** 주식현재가 호가/예상체결. */
        private static final String TR_ORDERBOOK = "FHKST01010200";

        /** 국내주식 재무 3종: 손익계산서 · 재무비율 · 안정성비율. */
        private static final Set<String> TR_FINANCE =
                Set.of("FHKST66430200", "FHKST66430300", "FHKST66430600");

        /**
         * 캐시 대상 TR_ID → (신선 TTL, stale 보관 기간).
         *
         * <p><b>allowlist 인 이유</b>: 캐시는 {@code KisApiClient} 라는 모든 KIS 호출의 공통
         * 관문에 붙어 있다. 여기서 무엇이든 캐시하면 잔고·주문가능금액·체결내역처럼
         * <b>절대 낡으면 안 되는 조회</b>까지 캐시되어 매매 판단이 오래된 값으로 이뤄진다.
         * 그래서 "종목 상세 화면이 반복 조회하는 읽기 전용 시세/재무" 5개 TR 만 명시적으로 연다.
         *
         * @return 캐시 대상이 아니면 null
         */
        public CachePolicy policyFor(String trId) {
            if (trId == null) {
                return null;
            }
            if (TR_PRICE.equals(trId) || TR_ORDERBOOK.equals(trId)) {
                return new CachePolicy(quoteTtl, quoteStaleGrace);
            }
            if (TR_FINANCE.contains(trId)) {
                return new CachePolicy(financeTtl, financeStaleGrace);
            }
            return null;
        }
    }

    /**
     * @param freshTtl   이 기간 안이면 KIS 를 호출하지 않고 캐시를 그대로 쓴다
     * @param staleGrace freshTtl 이 지나도 이 기간 안이면, KIS 호출 실패 시 마지막 성공값으로 폴백한다
     */
    public record CachePolicy(Duration freshTtl, Duration staleGrace) {}
}
