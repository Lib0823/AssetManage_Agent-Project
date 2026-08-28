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

        /**
         * 버킷 최대 용량 = 순간 버스트 허용량.
         *
         * <p>충전 속도(5/s)의 2배로 둔다. 화면 하나가 KIS 를 여러 번 두드리는 구간이 실제로 있고
         * (종목 상세 재무 탭 = 손익/재무비율/안정성 + 현재가 4연속), 용량을 충전 속도와 같게 두면
         * 그 4연속이 버킷을 그대로 비워 뒤따르는 다른 사용자의 첫 조회가 거부된다. 2초치 여유를
         * 두면 이런 짧은 묶음은 흡수하면서 <b>지속</b> 호출률은 여전히 5/s 로 묶인다.
         *
         * <p>더 크게 올리지 않는 이유: KIS 의 실제 한도가 문서로 확정돼 있지 않아, 버스트 여유는
         * "한 화면이 내는 묶음"을 흡수할 만큼만 두는 편이 안전하다.
         */
        private int capacity = 10;

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

        /**
         * 장내채권 시세 2종: 현재가 · 호가.
         *
         * <p>주식 시세와 같은 성격(읽기 전용, 화면이 반복 조회)이라 같은 TTL 을 쓴다. 장내채권은
         * 거래가 드물어 30초 신선도가 오히려 주식보다 여유롭다.
         *
         * <p>여기에 넣은 진짜 이유는 두 가지다. (1) 캐시가 없으면 stale-if-error 폴백도 없어
         * KIS 장애 시 채권 화면이 즉시 빈 값이 된다. (2) 공개 시세 경로는 <b>앱 단위 단일 키</b>를
         * 쓰므로 토큰 버킷 하나를 모든 익명 사용자가 공유하는데, 채권 상세가 한 번에 4개 API 를
         * 부른다 — 캐시 히트는 토큰을 소비하지 않으므로 채권 조회가 주식 시세 여력을 잠식하지 않는다.
         *
         * <p><b>잔고(CTSC8407R)·매도(TTTC0958U)·체결(CTSC8013R)은 절대 넣지 않는다.</b>
         * 주문·잔고를 캐시하면 "주문이 나간 것처럼 보이지만 실제로는 안 나간" 상태가 만들어진다.
         */
        private static final Set<String> TR_BOND_QUOTE =
                Set.of("FHKBJ773400C0", "FHKBJ773401C0");

        /** 국내주식 재무 3종: 손익계산서 · 재무비율 · 안정성비율. */
        private static final Set<String> TR_FINANCE =
                Set.of("FHKST66430200", "FHKST66430300", "FHKST66430600");

        /**
         * 캐시 대상 TR_ID → (신선 TTL, stale 보관 기간).
         *
         * <p><b>allowlist 인 이유</b>: 캐시는 {@code KisApiClient} 라는 모든 KIS 호출의 공통
         * 관문에 붙어 있다. 여기서 무엇이든 캐시하면 잔고·주문가능금액·체결내역처럼
         * <b>절대 낡으면 안 되는 조회</b>까지 캐시되어 매매 판단이 오래된 값으로 이뤄진다.
         * 그래서 "상세 화면이 반복 조회하는 읽기 전용 시세/재무" 7개 TR(주식 시세 2 · 재무 3 ·
         * 채권 시세 2)만 명시적으로 연다.
         *
         * @return 캐시 대상이 아니면 null
         */
        public CachePolicy policyFor(String trId) {
            if (trId == null) {
                return null;
            }
            if (TR_PRICE.equals(trId) || TR_ORDERBOOK.equals(trId) || TR_BOND_QUOTE.contains(trId)) {
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
